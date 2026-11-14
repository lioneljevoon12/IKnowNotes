from rest_framework import serializers
from .models import Attachment, Folder, Note
from django.contrib.auth.models import User


class FolderSerializer(serializers.ModelSerializer):
    class Meta:
        model = Folder
        fields = [
            'id',
            'name',
            'created_at',
            'updated_at',
        ]
        read_only_fields = ['id', 'created_at', 'updated_at']


class AttachmentSerializer(serializers.ModelSerializer):
    class Meta:
        model = Attachment
        fields = ['id', 'original_name', 'content_type', 'size', 'created_at']
        read_only_fields = fields


class NoteSerializer(serializers.ModelSerializer):
    attachments = AttachmentSerializer(many=True, read_only=True)

    folder = serializers.PrimaryKeyRelatedField(
        queryset=Folder.objects.all(),
        required=False,
        allow_null=True
    )

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        request = self.context.get('request')
        if request and hasattr(request, 'user') and request.user.is_authenticated:
            self.fields['folder'].queryset = Folder.objects.filter(user=request.user)

    def validate_folder(self, value):
        if value is not None:
            request = self.context.get('request')
            if request and hasattr(request, 'user') and request.user.is_authenticated and value.user != request.user:
                raise serializers.ValidationError("Folder tidak ditemukan.")
        return value

    class Meta:
        model = Note
        fields = [
            'id',
            'folder',
            'title',
            'content',
            'color',
            'is_pinned',
            'is_archived',
            'deleted_at',
            'reminder_time',
            'is_reminded',
            'attachments',
            'created_at',
            'updated_at',
        ]
        read_only_fields = [
            'id',
            'deleted_at',
            'attachments',
            'is_reminded',
            'created_at',
            'updated_at',
        ]


class RegisterSerializer(serializers.ModelSerializer):
    password = serializers.CharField(
        write_only=True,
        min_length=8
    )

    class Meta:
        model = User
        fields = ('username', 'password')

    def create(self, validated_data):
        user = User.objects.create_user(
            username=validated_data['username'],
            password=validated_data['password']
        )
        return user
