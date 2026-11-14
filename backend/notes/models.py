from django.db import models
from django.db.models.signals import post_delete
from django.dispatch import receiver
from django.contrib.auth.models import User


class Folder(models.Model):
    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='folders'
    )
    name = models.CharField(max_length=100)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.name


class Note(models.Model):
    user = models.ForeignKey(
        User,
        on_delete=models.CASCADE,
        related_name='notes'
    )
    folder = models.ForeignKey(
        Folder,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name='notes'
    )

    title = models.CharField(max_length=200)
    content = models.TextField()

    color = models.CharField(
        max_length=20,
        blank=True,
        null=True
    )

    is_pinned = models.BooleanField(default=False)
    is_archived = models.BooleanField(default=False)

    # Soft delete: a note with deleted_at set lives in the trash until it is
    # restored, permanently deleted, or purged after TRASH_RETENTION_DAYS.
    deleted_at = models.DateTimeField(null=True, blank=True, db_index=True)

    reminder_time = models.DateTimeField(null=True, blank=True)
    is_reminded = models.BooleanField(default=False)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return self.title


def attachment_upload_path(instance, filename):
    # The file name was already replaced by an unguessable uuid in the validator.
    return f'attachments/user_{instance.note.user_id}/{filename}'


class Attachment(models.Model):
    note = models.ForeignKey(
        Note,
        on_delete=models.CASCADE,
        related_name='attachments'
    )
    file = models.FileField(upload_to=attachment_upload_path)
    original_name = models.CharField(max_length=255)
    content_type = models.CharField(max_length=100)
    size = models.PositiveIntegerField()
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['id']

    def __str__(self):
        return self.original_name


@receiver(post_delete, sender=Attachment)
def delete_attachment_file(sender, instance, **kwargs):
    # Runs for single deletes and for cascades (deleting a note), so files never linger.
    instance.file.delete(save=False)
