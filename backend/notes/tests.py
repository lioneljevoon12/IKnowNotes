from datetime import timedelta

from django.test import TestCase
from django.contrib.auth.models import User
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework import status
from notes.models import Attachment, Folder, Note
from notes.views import TRASH_RETENTION_DAYS


class IKnowNotesApiTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create_user(username='testuser', password='password123')
        self.other_user = User.objects.create_user(username='otheruser', password='password123')
        
        # Authenticate as testuser
        response = self.client.post('/api/token/', {
            'username': 'testuser',
            'password': 'password123'
        })
        self.token = response.data['access']
        self.client.credentials(HTTP_AUTHORIZATION=f'Bearer {self.token}')

    def test_register(self):
        client = APIClient()
        response = client.post('/api/register/', {
            'username': 'newuser',
            'password': 'password123'
        })
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertIn('access', response.data)
        self.assertIn('refresh', response.data)

    def test_folder_crud_and_user_isolation(self):
        # Create folder
        create_res = self.client.post('/api/folders/', {'name': 'Kuliah'})
        self.assertEqual(create_res.status_code, status.HTTP_201_CREATED)
        folder_id = create_res.data['id']

        # List folders
        list_res = self.client.get('/api/folders/')
        self.assertEqual(list_res.status_code, status.HTTP_200_OK)
        self.assertEqual(len(list_res.data), 1)

        # Other user's folder should not be visible
        other_folder = Folder.objects.create(user=self.other_user, name='Rahasia')
        list_res2 = self.client.get('/api/folders/')
        self.assertEqual(len(list_res2.data), 1)
        self.assertEqual(list_res2.data[0]['id'], folder_id)

    def test_note_crud_and_archive_unarchive(self):
        # Create note
        note_res = self.client.post('/api/notes/', {
            'title': 'Catatan Pertama',
            'content': 'Ini isi catatan.',
            'color': 'yellow',
            'is_pinned': False
        })
        self.assertEqual(note_res.status_code, status.HTTP_201_CREATED)
        note_id = note_res.data['id']

        # List notes (active)
        active_list = self.client.get('/api/notes/')
        self.assertEqual(active_list.status_code, status.HTTP_200_OK)
        self.assertEqual(len(active_list.data['results']), 1)

        # Archive note
        archive_res = self.client.patch(f'/api/notes/{note_id}/archive/')
        self.assertEqual(archive_res.status_code, status.HTTP_200_OK)

        # Now active list should be empty
        active_list2 = self.client.get('/api/notes/')
        self.assertEqual(len(active_list2.data['results']), 0)

        # Archived list should have it
        archived_list = self.client.get('/api/notes/archived/')
        self.assertEqual(archived_list.status_code, status.HTTP_200_OK)
        self.assertEqual(len(archived_list.data['results']), 1)
        self.assertEqual(archived_list.data['results'][0]['id'], note_id)

        # Detail of archived note should be accessible
        detail_res = self.client.get(f'/api/notes/{note_id}/')
        self.assertEqual(detail_res.status_code, status.HTTP_200_OK)

        # Unarchive note (tests our fix for the 404 bug)
        unarchive_res = self.client.patch(f'/api/notes/{note_id}/unarchive/')
        self.assertEqual(unarchive_res.status_code, status.HTTP_200_OK)

        # Active list should have it again
        active_list3 = self.client.get('/api/notes/')
        self.assertEqual(len(active_list3.data['results']), 1)

    def _create_note(self, title='Catatan'):
        res = self.client.post('/api/notes/', {'title': title, 'content': 'Isi'})
        self.assertEqual(res.status_code, status.HTTP_201_CREATED)
        return res.data['id']

    def test_delete_moves_note_to_trash(self):
        note_id = self._create_note()

        delete_res = self.client.delete(f'/api/notes/{note_id}/')
        self.assertEqual(delete_res.status_code, status.HTTP_204_NO_CONTENT)

        # Row still exists, flagged as deleted
        self.assertIsNotNone(Note.objects.get(id=note_id).deleted_at)

        # Hidden from the normal views
        self.assertEqual(len(self.client.get('/api/notes/').data['results']), 0)
        self.assertEqual(len(self.client.get('/api/notes/archived/').data['results']), 0)
        self.assertEqual(
            self.client.get(f'/api/notes/{note_id}/').status_code,
            status.HTTP_404_NOT_FOUND,
        )

        # Visible in the trash
        trash = self.client.get('/api/notes/trash/')
        self.assertEqual(trash.status_code, status.HTTP_200_OK)
        self.assertEqual([n['id'] for n in trash.data['results']], [note_id])
        self.assertIsNotNone(trash.data['results'][0]['deleted_at'])

    def test_trashed_note_cannot_be_modified_until_restored(self):
        note_id = self._create_note()
        self.client.delete(f'/api/notes/{note_id}/')

        self.assertEqual(
            self.client.patch(f'/api/notes/{note_id}/', {'title': 'x'}).status_code,
            status.HTTP_404_NOT_FOUND,
        )
        self.assertEqual(
            self.client.patch(f'/api/notes/{note_id}/archive/').status_code,
            status.HTTP_404_NOT_FOUND,
        )
        # Deleting again is a 404, not a second soft delete
        self.assertEqual(
            self.client.delete(f'/api/notes/{note_id}/').status_code,
            status.HTTP_404_NOT_FOUND,
        )

    def test_restore_note_from_trash(self):
        note_id = self._create_note()
        self.client.delete(f'/api/notes/{note_id}/')

        restore_res = self.client.patch(f'/api/notes/{note_id}/restore/')
        self.assertEqual(restore_res.status_code, status.HTTP_200_OK)

        self.assertEqual(len(self.client.get('/api/notes/').data['results']), 1)
        self.assertEqual(len(self.client.get('/api/notes/trash/').data['results']), 0)
        self.assertIsNone(self.client.get(f'/api/notes/{note_id}/').data['deleted_at'])

    def test_restore_active_note_is_404(self):
        note_id = self._create_note()
        res = self.client.patch(f'/api/notes/{note_id}/restore/')
        self.assertEqual(res.status_code, status.HTTP_404_NOT_FOUND)

    def test_permanent_delete_only_works_from_trash(self):
        note_id = self._create_note()

        # An active note cannot be permanently deleted directly
        res = self.client.delete(f'/api/notes/{note_id}/permanent/')
        self.assertEqual(res.status_code, status.HTTP_404_NOT_FOUND)
        self.assertTrue(Note.objects.filter(id=note_id).exists())

        self.client.delete(f'/api/notes/{note_id}/')
        res = self.client.delete(f'/api/notes/{note_id}/permanent/')
        self.assertEqual(res.status_code, status.HTTP_204_NO_CONTENT)
        self.assertFalse(Note.objects.filter(id=note_id).exists())

    def test_empty_trash_only_removes_trashed_notes_of_current_user(self):
        trashed_id = self._create_note('Sampah')
        kept_id = self._create_note('Aman')
        self.client.delete(f'/api/notes/{trashed_id}/')

        other_note = Note.objects.create(
            user=self.other_user, title='Milik lain', content='x',
            deleted_at=timezone.now(),
        )

        res = self.client.delete('/api/notes/trash/empty/')
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        self.assertEqual(res.data['deleted'], 1)

        self.assertFalse(Note.objects.filter(id=trashed_id).exists())
        self.assertTrue(Note.objects.filter(id=kept_id).exists())
        self.assertTrue(Note.objects.filter(id=other_note.id).exists())

    def test_trash_is_isolated_between_users(self):
        other_note = Note.objects.create(
            user=self.other_user, title='Milik lain', content='x',
            deleted_at=timezone.now(),
        )
        self.assertEqual(len(self.client.get('/api/notes/trash/').data['results']), 0)
        self.assertEqual(
            self.client.patch(f'/api/notes/{other_note.id}/restore/').status_code,
            status.HTTP_404_NOT_FOUND,
        )
        self.assertEqual(
            self.client.delete(f'/api/notes/{other_note.id}/permanent/').status_code,
            status.HTTP_404_NOT_FOUND,
        )

    def test_expired_trash_is_purged(self):
        old = Note.objects.create(
            user=self.user, title='Lama', content='x',
            deleted_at=timezone.now() - timedelta(days=TRASH_RETENTION_DAYS + 1),
        )
        recent = Note.objects.create(
            user=self.user, title='Baru', content='x',
            deleted_at=timezone.now() - timedelta(days=TRASH_RETENTION_DAYS - 1),
        )

        trash = self.client.get('/api/notes/trash/')
        self.assertEqual([n['id'] for n in trash.data['results']], [recent.id])
        self.assertFalse(Note.objects.filter(id=old.id).exists())

    def test_cannot_assign_other_user_folder(self):
        other_folder = Folder.objects.create(user=self.other_user, name='Folder Orang Lain')
        res = self.client.post('/api/notes/', {
            'title': 'Curi Folder',
            'content': 'Tes',
            'folder': other_folder.id
        })
        self.assertEqual(res.status_code, status.HTTP_400_BAD_REQUEST)


JPEG_BYTES = b'\xff\xd8\xff\xe0' + b'\x00' * 200
PNG_BYTES = b'\x89PNG\r\n\x1a\n' + b'\x00' * 200


class PaginationTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.user = User.objects.create_user(username='pager', password='password123')
        self.client.force_authenticate(self.user)
        for i in range(5):
            Note.objects.create(user=self.user, title=f'Catatan {i}', content='x')

    def test_list_is_paginated_with_count_next_and_previous(self):
        res = self.client.get('/api/notes/', {'page_size': 2})
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        self.assertEqual(res.data['count'], 5)
        self.assertEqual(len(res.data['results']), 2)
        self.assertIsNotNone(res.data['next'])
        self.assertIsNone(res.data['previous'])

    def test_walking_every_page_returns_each_note_exactly_once(self):
        seen = []
        page = 1
        while True:
            res = self.client.get('/api/notes/', {'page_size': 2, 'page': page, 'ordering': 'id'})
            seen += [n['id'] for n in res.data['results']]
            if res.data['next'] is None:
                break
            page += 1
        self.assertEqual(len(seen), 5)
        self.assertEqual(seen, sorted(seen))
        self.assertEqual(len(set(seen)), 5)

    def test_page_size_is_capped(self):
        res = self.client.get('/api/notes/', {'page_size': 10000})
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        self.assertEqual(len(res.data['results']), 5)  # only 5 exist, cap is 100

    def test_archived_and_trash_are_paginated_too(self):
        archived = Note.objects.create(user=self.user, title='Arsip', content='x', is_archived=True)
        trashed = Note.objects.create(
            user=self.user, title='Sampah', content='x', deleted_at=timezone.now()
        )
        res = self.client.get('/api/notes/archived/')
        self.assertEqual([n['id'] for n in res.data['results']], [archived.id])
        self.assertEqual(res.data['count'], 1)
        res = self.client.get('/api/notes/trash/')
        self.assertEqual([n['id'] for n in res.data['results']], [trashed.id])

    def test_search_works_with_pagination(self):
        res = self.client.get('/api/notes/', {'search': 'Catatan 3'})
        self.assertEqual(res.data['count'], 1)

    def test_folders_stay_a_plain_list(self):
        Folder.objects.create(user=self.user, name='Kuliah')
        res = self.client.get('/api/folders/')
        self.assertIsInstance(res.data, list)


class AttachmentTests(TestCase):
    def setUp(self):
        import shutil
        import tempfile
        from django.test import override_settings

        self.media = tempfile.mkdtemp()
        self.override = override_settings(MEDIA_ROOT=self.media)
        self.override.enable()
        self.addCleanup(self.override.disable)
        self.addCleanup(shutil.rmtree, self.media, True)

        self.client = APIClient()
        self.user = User.objects.create_user(username='photo', password='password123')
        self.other = User.objects.create_user(username='intruder', password='password123')
        self.client.force_authenticate(self.user)
        self.note = Note.objects.create(user=self.user, title='Dengan foto', content='x')

    def _upload(self, note=None, content=JPEG_BYTES, name='foto.jpg', client=None):
        from django.core.files.uploadedfile import SimpleUploadedFile
        note = note or self.note
        return (client or self.client).post(
            f'/api/notes/{note.id}/attachments/',
            {'file': SimpleUploadedFile(name, content, content_type='image/jpeg')},
            format='multipart',
        )

    def _files_on_disk(self):
        import os
        return [f for _, _, files in os.walk(self.media) for f in files]

    def test_upload_returns_metadata_and_stores_file_with_server_chosen_name(self):
        res = self._upload(name='evil name.jpg')
        self.assertEqual(res.status_code, status.HTTP_201_CREATED)
        self.assertEqual(res.data['content_type'], 'image/jpeg')
        self.assertEqual(res.data['size'], len(JPEG_BYTES))
        self.assertTrue(res.data['original_name'].endswith('evil name.jpg'))

        files = self._files_on_disk()
        self.assertEqual(len(files), 1)
        self.assertTrue(files[0].endswith('.jpg'))
        self.assertNotIn('evil', files[0])

    def test_attachments_are_embedded_in_note_detail_and_list(self):
        self._upload()
        detail = self.client.get(f'/api/notes/{self.note.id}/')
        self.assertEqual(len(detail.data['attachments']), 1)
        listing = self.client.get('/api/notes/')
        self.assertEqual(len(listing.data['results'][0]['attachments']), 1)

    def test_upload_updates_the_note_timestamp(self):
        before = Note.objects.get(pk=self.note.pk).updated_at
        self._upload()
        self.assertGreater(Note.objects.get(pk=self.note.pk).updated_at, before)

    def test_type_is_detected_from_content_not_from_name(self):
        res = self._upload(content=PNG_BYTES, name='sebenarnya.png.jpg')
        self.assertEqual(res.data['content_type'], 'image/png')
        self.assertTrue(self._files_on_disk()[0].endswith('.png'))

    def test_rejects_non_images(self):
        res = self._upload(content=b'<?php echo 1; ?>', name='shell.jpg')
        self.assertEqual(res.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(self._files_on_disk(), [])

    def test_rejects_oversized_files(self):
        from unittest import mock
        with mock.patch('notes.attachments.MAX_ATTACHMENT_BYTES', 100):
            res = self._upload()
        self.assertEqual(res.status_code, status.HTTP_400_BAD_REQUEST)

    def test_limits_attachments_per_note(self):
        from unittest import mock
        with mock.patch('notes.views.MAX_ATTACHMENTS_PER_NOTE', 2):
            self.assertEqual(self._upload().status_code, status.HTTP_201_CREATED)
            self.assertEqual(self._upload().status_code, status.HTTP_201_CREATED)
            self.assertEqual(self._upload().status_code, status.HTTP_400_BAD_REQUEST)

    def test_missing_file_is_a_400(self):
        res = self.client.post(f'/api/notes/{self.note.id}/attachments/', {}, format='multipart')
        self.assertEqual(res.status_code, status.HTTP_400_BAD_REQUEST)

    def test_owner_can_download_the_file(self):
        attachment_id = self._upload().data['id']
        res = self.client.get(f'/api/attachments/{attachment_id}/file/')
        self.assertEqual(res.status_code, status.HTTP_200_OK)
        self.assertEqual(res['Content-Type'], 'image/jpeg')
        self.assertEqual(b''.join(res.streaming_content), JPEG_BYTES)
        self.assertIn('private', res['Cache-Control'])

    def test_download_requires_login(self):
        attachment_id = self._upload().data['id']
        res = APIClient().get(f'/api/attachments/{attachment_id}/file/')
        self.assertEqual(res.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_other_users_cannot_download_delete_or_upload(self):
        attachment_id = self._upload().data['id']
        intruder = APIClient()
        intruder.force_authenticate(self.other)

        self.assertEqual(
            intruder.get(f'/api/attachments/{attachment_id}/file/').status_code,
            status.HTTP_404_NOT_FOUND,
        )
        self.assertEqual(
            intruder.delete(f'/api/attachments/{attachment_id}/').status_code,
            status.HTTP_404_NOT_FOUND,
        )
        self.assertEqual(self._upload(client=intruder).status_code, status.HTTP_404_NOT_FOUND)
        self.assertEqual(len(self._files_on_disk()), 1)

    def test_deleting_an_attachment_removes_the_file(self):
        attachment_id = self._upload().data['id']
        res = self.client.delete(f'/api/attachments/{attachment_id}/')
        self.assertEqual(res.status_code, status.HTTP_204_NO_CONTENT)
        self.assertEqual(Attachment.objects.count(), 0)
        self.assertEqual(self._files_on_disk(), [])

    def test_cannot_attach_to_a_trashed_note(self):
        self.client.delete(f'/api/notes/{self.note.id}/')
        self.assertEqual(self._upload().status_code, status.HTTP_404_NOT_FOUND)

    def test_permanently_deleting_a_note_removes_its_files(self):
        self._upload()
        self._upload(content=PNG_BYTES)
        self.assertEqual(len(self._files_on_disk()), 2)

        self.client.delete(f'/api/notes/{self.note.id}/')
        self.client.delete(f'/api/notes/{self.note.id}/permanent/')
        self.assertEqual(Attachment.objects.count(), 0)
        self.assertEqual(self._files_on_disk(), [])

    def test_emptying_the_trash_removes_files_too(self):
        self._upload()
        self.client.delete(f'/api/notes/{self.note.id}/')
        self.client.delete('/api/notes/trash/empty/')
        self.assertEqual(self._files_on_disk(), [])
