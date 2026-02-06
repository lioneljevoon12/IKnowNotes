from rest_framework import viewsets, permissions
from .models import Folder, Note
from .serializers import FolderSerializer, NoteSerializer, RegisterSerializer
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status, permissions
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework.decorators import action
from rest_framework import filters
from django_filters.rest_framework import DjangoFilterBackend

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

    def get_queryset(self):
        return (
            Note.objects
            .filter(
                user=self.request.user,
                is_archived=False
            )
            .order_by('-is_pinned', '-updated_at')
        )

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)

    @action(detail=False, methods=['get'])
    def archived(self, request):
        archived_notes = (
            Note.objects
            .filter(user=request.user, is_archived=True)
            .order_by('-updated_at')
        )
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
    
    filter_backends = [
        filters.SearchFilter,
        DjangoFilterBackend,
        filters.OrderingFilter,
    ]

    search_fields = ['title', 'content']

    filterset_fields = ['folder']

    ordering_fields = ['updated_at', 'is_pinned']

    ordering = ['-is_pinned', '-updated_at']

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