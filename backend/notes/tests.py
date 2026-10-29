from django.test import TestCase
from django.contrib.auth.models import User
from rest_framework.test import APIClient
from rest_framework import status
from notes.models import Folder, Note


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

    def test_cannot_assign_other_user_folder(self):
        other_folder = Folder.objects.create(user=self.other_user, name='Folder Orang Lain')
        res = self.client.post('/api/notes/', {
            'title': 'Curi Folder',
            'content': 'Tes',
            'folder': other_folder.id
        })
        self.assertEqual(res.status_code, status.HTTP_400_BAD_REQUEST)
