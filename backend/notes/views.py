from datetime import timedelta

from django.utils import timezone
from rest_framework import viewsets, permissions, status, filters
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework.decorators import action
from django_filters.rest_framework import DjangoFilterBackend
from .models import Folder, Note
from .serializers import FolderSerializer, NoteSerializer, RegisterSerializer

# Trashed notes are permanently removed this many days after deletion.
TRASH_RETENTION_DAYS = 30
TRASH_ACTIONS = ('trash', 'empty_trash', 'restore', 'permanent_delete')


class FolderViewSet(viewsets.ModelViewSet):
    serializer_class = FolderSerializer
    permission_classes = [permissions.IsAuthenticated]

    def get_queryset(self):
        return Folder.objects.filter(user=self.request.user)

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)


class NoteViewSet(viewsets.ModelViewSet):
    serializer_class = NoteSerializer
    permission_classes = [permissions.IsAuthenticated]

    filter_backends = [
        filters.SearchFilter,
        DjangoFilterBackend,
        filters.OrderingFilter,
    ]
    search_fields = ['title', 'content']
    filterset_fields = ['folder']
    ordering_fields = ['updated_at', 'is_pinned']
    ordering = ['-is_pinned', '-updated_at']

    def get_queryset(self):
        user = self.request.user
        if not user.is_authenticated:
            return Note.objects.none()

        # Trash actions only ever see notes that are in the trash.
        if self.action in TRASH_ACTIONS:
            return (
                Note.objects
                .filter(user=user, deleted_at__isnull=False)
                .order_by('-deleted_at')
            )

        # Everything else never sees trashed notes (they 404 until restored).
        notes = Note.objects.filter(user=user, deleted_at__isnull=True)

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
        serializer = self.get_serializer(trashed_notes, many=True)
        return Response(serializer.data)

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
        serializer = self.get_serializer(archived_notes, many=True)
        return Response(serializer.data)

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
