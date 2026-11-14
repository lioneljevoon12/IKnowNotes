"""Validation helpers for image attachments."""
import uuid

from rest_framework import serializers

MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024  # 10 MB per image
MAX_ATTACHMENTS_PER_NOTE = 10

EXTENSIONS = {
    'image/jpeg': '.jpg',
    'image/png': '.png',
    'image/gif': '.gif',
    'image/webp': '.webp',
}


def sniff_content_type(header):
    """
    Detects the image type from the file's first bytes. The client-supplied
    content type and file name are never trusted.
    """
    if header.startswith(b'\xff\xd8\xff'):
        return 'image/jpeg'
    if header.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'image/png'
    if header.startswith((b'GIF87a', b'GIF89a')):
        return 'image/gif'
    if header[:4] == b'RIFF' and header[8:12] == b'WEBP':
        return 'image/webp'
    return None


def validate_image_upload(upload):
    """
    Checks size and real file type; returns the detected content type.
    Also renames the upload to an unguessable server-chosen name.
    """
    if upload.size > MAX_ATTACHMENT_BYTES:
        limit_mb = MAX_ATTACHMENT_BYTES // (1024 * 1024)
        raise serializers.ValidationError(
            {'file': [f'Ukuran gambar maksimal {limit_mb} MB.']}
        )

    header = upload.read(16)
    upload.seek(0)
    content_type = sniff_content_type(header)
    if content_type is None:
        raise serializers.ValidationError(
            {'file': ['File harus berupa gambar (JPEG, PNG, GIF, atau WebP).']}
        )

    upload.name = f'{uuid.uuid4().hex}{EXTENSIONS[content_type]}'
    return content_type
