from rest_framework.pagination import PageNumberPagination


class NotePagination(PageNumberPagination):
    """
    `?page=2&page_size=20` — responses look like
    {"count": 135, "next": "...?page=3", "previous": "...?page=1", "results": [...]}.
    """
    page_size = 50
    page_size_query_param = 'page_size'
    max_page_size = 100
