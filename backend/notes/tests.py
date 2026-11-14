from datetime import timedelta

from django.test import TestCase
from django.contrib.auth.models import User
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework import status
from notes.models import Folder, Note
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
        self.assertEqual(len(active_list.data), 1)

        # Archive note
        archive_res = self.client.patch(f'/api/notes/{note_id}/archive/')
        self.assertEqual(archive_res.status_code, status.HTTP_200_OK)

        # Now active list should be empty
        active_list2 = self.client.get('/api/notes/')
        self.assertEqual(len(active_list2.data), 0)

        # Archived list should have it
        archived_list = self.client.get('/api/notes/archived/')
        self.assertEqual(archived_list.status_code, status.HTTP_200_OK)
        self.assertEqual(len(archived_list.data), 1)
        self.assertEqual(archived_list.data[0]['id'], note_id)

        # Detail of archived note should be accessible
        detail_res = self.client.get(f'/api/notes/{note_id}/')
        self.assertEqual(detail_res.status_code, status.HTTP_200_OK)

        # Unarchive note (tests our fix for the 404 bug)
        unarchive_res = self.client.patch(f'/api/notes/{note_id}/unarchive/')
        self.assertEqual(unarchive_res.status_code, status.HTTP_200_OK)

        # Active list should have it again
        active_list3 = self.client.get('/api/notes/')
        self.assertEqual(len(active_list3.data), 1)

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
        self.assertEqual(len(self.client.get('/api/notes/').data), 0)
        self.assertEqual(len(self.client.get('/api/notes/archived/').data), 0)
        self.assertEqual(
            self.client.get(f'/api/notes/{note_id}/').status_code,
            status.HTTP_404_NOT_FOUND,
        )

        # Visible in the trash
        trash = self.client.get('/api/notes/trash/')
        self.assertEqual(trash.status_code, status.HTTP_200_OK)
        self.assertEqual([n['id'] for n in trash.data], [note_id])
        self.assertIsNotNone(trash.data[0]['deleted_at'])

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

        self.assertEqual(len(self.client.get('/api/notes/').data), 1)
        self.assertEqual(len(self.client.get('/api/notes/trash/').data), 0)
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
        self.assertEqual(len(self.client.get('/api/notes/trash/').data), 0)
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
        self.assertEqual([n['id'] for n in trash.data], [recent.id])
        self.assertFalse(Note.objects.filter(id=old.id).exists())

    def test_cannot_assign_other_user_folder(self):
        other_folder = Folder.objects.create(user=self.other_user, name='Folder Orang Lain')
        res = self.client.post('/api/notes/', {
            'title': 'Curi Folder',
            'content': 'Tes',
            'folder': other_folder.id
        })
        self.assertEqual(res.status_code, status.HTTP_400_BAD_REQUEST)
