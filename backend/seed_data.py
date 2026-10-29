import os
import django

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')
django.setup()

from django.contrib.auth.models import User
from notes.models import Folder, Note

def seed():
    # Create or update default user 'leon'
    username = 'leon'
    password = 'password123'
    user, created = User.objects.get_or_create(username=username)
    user.set_password(password)
    user.save()
    print(f"User '{username}' ready (password: '{password}').")

    # Clear existing dummy notes & folders for this user
    Note.objects.filter(user=user).delete()
    Folder.objects.filter(user=user).delete()

    # Create folders matching mockup
    folder_important = Folder.objects.create(user=user, name="Important")
    folder_lecture = Folder.objects.create(user=user, name="Lecture notes")
    folder_todo = Folder.objects.create(user=user, name="To-do lists")
    folder_shopping = Folder.objects.create(user=user, name="Shopping list")

    print("Folders created successfully.")

    # Create notes
    notes_data = [
        {
            "title": "Product Meeting",
            "content": "Review of Previous Action Items\n\nProduct Development Update\n\nUser Feedback and Customer Insights\n\nCompetitive Analysis\n\nRoadmap Discussion",
            "color": "blue",
            "folder": folder_important,
            "is_pinned": True,
            "is_archived": False,
        },
        {
            "title": "To-do list",
            "content": "1. Reply to emails\n2. Prepare presentation slides for the marketing meeting\n3. Conduct research on competitor products\n4. Schedule and plan customer interviews\n5. Take a break and recharge",
            "color": "pink",
            "folder": folder_todo,
            "is_pinned": True,
            "is_archived": False,
        },
        {
            "title": "Shopping list",
            "content": "1. Rice\n2. Pasta\n3. Cereal\n4. Yogurt\n5. Cheese\n6. Butter",
            "color": "yellow",
            "folder": folder_shopping,
            "is_pinned": False,
            "is_archived": False,
        },
        {
            "title": "Product Meeting (Update)",
            "content": "Review of Previous Action Items\n\nProduct Update\n\nUser Feedback and Customer Insights\n\nCompetitive Analysis\n\nRoadmap Discussion",
            "color": "green",
            "folder": folder_lecture,
            "is_pinned": False,
            "is_archived": False,
        },
        {
            "title": "Product Management",
            "content": "Product management is a strategic role within a company that involves overseeing the development and management of a product or suite of products throughout their lifecycle. The primary goal of a product manager is to create and deliver products that meet customer needs, align with business objectives, and generate value for the organization.\n\n1. Market research and analysis:\nProduct managers conduct market research to understand customer needs, preferences, and trends.\n\n2. Product strategy:\nProduct managers define the product vision, goals, and strategy based on market research and business objectives.",
            "color": "yellow",
            "folder": folder_lecture,
            "is_pinned": False,
            "is_archived": False,
        },
        {
            "title": "Notes",
            "content": "Share insights and findings from recent competitive analysis.",
            "color": "pink",
            "folder": folder_important,
            "is_pinned": False,
            "is_archived": False,
        },
        {
            "title": "Sprint Review Q3 (Arsip)",
            "content": "Catatan evaluasi sprint sebelumnya yang sudah selesai dan disimpan ke arsip.",
            "color": "default",
            "folder": folder_important,
            "is_pinned": False,
            "is_archived": True,
        }
    ]

    for data in notes_data:
        Note.objects.create(user=user, **data)

    print(f"Created {len(notes_data)} sample notes successfully.")

if __name__ == '__main__':
    seed()
