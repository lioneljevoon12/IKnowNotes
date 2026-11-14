from datetime import timedelta

from django.utils import timezone
from django.http import FileResponse
from rest_framework import mixins, viewsets, permissions, status, filters
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework.decorators import action
from django_filters.rest_framework import DjangoFilterBackend
from .attachments import MAX_ATTACHMENTS_PER_NOTE, validate_image_upload
from .models import Attachment, Folder, Note
from .pagination import NotePagination
from .serializers import (
    AttachmentSerializer,
    FolderSerializer,
    NoteSerializer,
    RegisterSerializer,
)

# Trashed notes are permanently removed this many days after deletion.
TRASH_RETENTION_DAYS = 30
TRASH_ACTIONS = ('trash', 'empty_trash', 'restore', 'permanent_delete')


class FolderViewSet(viewsets.ModelViewSet):
    serializer_class = FolderSerializer
    permission_classes = [permissions.IsAuthenticated]
    pagination_class = None  # a user only has a handful of folders

    def get_queryset(self):
        return Folder.objects.filter(user=self.request.user)

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)


class NoteViewSet(viewsets.ModelViewSet):
    serializer_class = NoteSerializer
    permission_classes = [permissions.IsAuthenticated]
    pagination_class = NotePagination

    filter_backends = [
        filters.SearchFilter,
        DjangoFilterBackend,
        filters.OrderingFilter,
    ]
    search_fields = ['title', 'content']
    filterset_fields = ['folder']
    # 'id' lets clients walk every page in a stable order (?ordering=id) even
    # while notes are being edited; the extra '-id' makes the default order total.
    ordering_fields = ['updated_at', 'created_at', 'is_pinned', 'id']
    ordering = ['-is_pinned', '-updated_at', '-id']

    def _paginated_response(self, queryset):
        page = self.paginate_queryset(queryset)
        if page is not None:
            serializer = self.get_serializer(page, many=True)
            return self.get_paginated_response(serializer.data)
        return Response(self.get_serializer(queryset, many=True).data)

    def _touch(self, note_id):
        # Attachments live inside the note, so adding/removing one counts as an edit
        # (update() skips auto_now, hence the explicit timestamp).
        Note.objects.filter(pk=note_id).update(updated_at=timezone.now())

    def get_queryset(self):
        user = self.request.user
        if not user.is_authenticated:
            return Note.objects.none()

        all_notes = Note.objects.filter(user=user).prefetch_related('attachments')

        # Trash actions only ever see notes that are in the trash.
        if self.action in TRASH_ACTIONS:
            return (
                all_notes
                .filter(deleted_at__isnull=False)
                .order_by('-deleted_at')
            )

        # Everything else never sees trashed notes (they 404 until restored).
        notes = all_notes.filter(deleted_at__isnull=True)

        # For list action, only return active (non-archived) notes
        if self.action == 'list':
            return (
                notes
                .filter(is_archived=False)
                .order_by('-is_pinned', '-updated_at')
            )
        # For archived action, return archived notes
        elif self.action == 'archived':
            return (
                notes
                .filter(is_archived=True)
                .order_by('-updated_at')
            )

        # For retrieve, update, destroy, archive, unarchive, etc.,
        # allow access to both active and archived notes belonging to this user
        return notes.order_by('-updated_at')

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)

    def perform_destroy(self, instance):
        # DELETE /notes/{id}/ is a soft delete: move the note to the trash.
        instance.deleted_at = timezone.now()
        instance.save(update_fields=['deleted_at'])

    def _purge_expired_trash(self):
        cutoff = timezone.now() - timedelta(days=TRASH_RETENTION_DAYS)
        Note.objects.filter(
            user=self.request.user,
            deleted_at__isnull=False,
            deleted_at__lt=cutoff,
        ).delete()

    def list(self, request, *args, **kwargs):
        self._purge_expired_trash()
        return super().list(request, *args, **kwargs)

    @action(detail=False, methods=['get'])
    def trash(self, request):
        self._purge_expired_trash()
        trashed_notes = self.filter_queryset(self.get_queryset())
        return self._paginated_response(trashed_notes)

    @action(detail=False, methods=['delete'], url_path='trash/empty')
    def empty_trash(self, request):
        deleted_count, _ = self.get_queryset().delete()
        return Response({"status": "emptied", "deleted": deleted_count})

    @action(detail=True, methods=['patch'])
    def restore(self, request, pk=None):
        note = self.get_object()
        note.deleted_at = None
        note.save(update_fields=['deleted_at'])
        return Response({"status": "restored"})

    @action(detail=True, methods=['delete'], url_path='permanent')
    def permanent_delete(self, request, pk=None):
        # Only reachable for notes already in the trash (see get_queryset).
        note = self.get_object()
        note.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)

    @action(detail=False, methods=['get'])
    def archived(self, request):
        archived_notes = self.filter_queryset(self.get_queryset())
        return self._paginated_response(archived_notes)

    @action(detail=True, methods=['patch'])
    def archive(self, request, pk=None):
        note = self.get_object()
        note.is_archived = True
        note.save(update_fields=['is_archived'])
        return Response({"status": "archived"})

    @action(detail=True, methods=['patch'])
    def unarchive(self, request, pk=None):
        note = self.get_object()
        note.is_archived = False
        note.save(update_fields=['is_archived'])
        return Response({"status": "unarchived"})

    @action(
        detail=True,
        methods=['get', 'post'],
        url_path='attachments',
        parser_classes=[MultiPartParser, FormParser],
    )
    def attachments(self, request, pk=None):
        # Trashed notes 404 here (see get_queryset): restore them first.
        note = self.get_object()

        if request.method == 'GET':
            serializer = AttachmentSerializer(note.attachments.all(), many=True)
            return Response(serializer.data)

        upload = request.FILES.get('file')
        if upload is None:
            return Response(
                {'file': ['File wajib diunggah.']},
                status=status.HTTP_400_BAD_REQUEST,
            )
        if note.attachments.count() >= MAX_ATTACHMENTS_PER_NOTE:
            return Response(
                {'file': [f'Maksimal {MAX_ATTACHMENTS_PER_NOTE} lampiran per catatan.']},
                status=status.HTTP_400_BAD_REQUEST,
            )

        original_name = (upload.name or 'gambar')[-255:]
        content_type = validate_image_upload(upload)
        attachment = Attachment.objects.create(
            note=note,
            file=upload,
            original_name=original_name,
            content_type=content_type,
            size=upload.size,
        )
        self._touch(note.pk)
        return Response(
            AttachmentSerializer(attachment).data,
            status=status.HTTP_201_CREATED,
        )


class AttachmentViewSet(mixins.DestroyModelMixin, viewsets.GenericViewSet):
    """Download (authenticated, owner only) and delete single attachments."""
    serializer_class = AttachmentSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        return Attachment.objects.filter(note__user=self.request.user)

    def perform_destroy(self, instance):
        note_id = instance.note_id
        instance.delete()
        Note.objects.filter(pk=note_id).update(updated_at=timezone.now())

    @action(detail=True, methods=['get'], url_path='file')
    def file(self, request, pk=None):
        attachment = self.get_object()
        try:
            handle = attachment.file.open('rb')
        except FileNotFoundError:
            return Response(status=status.HTTP_404_NOT_FOUND)

        response = FileResponse(
            handle,
            content_type=attachment.content_type,
            filename=attachment.original_name,
        )
        # Files are private to the owner but immutable, so the phone may cache them.
        response['Cache-Control'] = 'private, max-age=86400'
        return response


class RegisterView(APIView):
    permission_classes = [permissions.AllowAny]

    def post(self, request):
        serializer = RegisterSerializer(data=request.data)
        if not serializer.is_valid():
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        user = serializer.save()
        refresh = RefreshToken.for_user(user)

        return Response(
            {
                "access": str(refresh.access_token),
                "refresh": str(refresh),
            },
            status=status.HTTP_201_CREATED
        )
