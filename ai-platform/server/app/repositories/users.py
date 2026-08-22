import uuid
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.entities import User


class UserRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def by_email(self, email: str) -> User | None:
        return await self.session.scalar(select(User).where(User.email == email, User.deleted_at.is_(None)))

    async def by_id(self, user_id: uuid.UUID) -> User | None:
        return await self.session.scalar(select(User).where(User.id == user_id, User.deleted_at.is_(None)))

    async def create(self, **values) -> User:
        user = User(**values)
        self.session.add(user)
        await self.session.flush()
        return user

    async def list(self, page: int, page_size: int, query: str | None = None, sort_by: str = "created_at", order: str = "desc") -> tuple[list[User], int]:
        stmt = select(User).where(User.deleted_at.is_(None))
        count_stmt = select(func.count(User.id)).where(User.deleted_at.is_(None))
        if query:
            pattern = f"%{query.strip().lower()}%"
            stmt = stmt.where(func.lower(User.email).like(pattern))
            count_stmt = count_stmt.where(func.lower(User.email).like(pattern))
        total = int(await self.session.scalar(count_stmt) or 0)
        columns = {"created_at": User.created_at, "email": User.email, "updated_at": User.updated_at}
        column = columns.get(sort_by, User.created_at)
        ordering = column.asc() if order == "asc" else column.desc()
        items = list((await self.session.scalars(stmt.order_by(ordering).offset((page-1)*page_size).limit(page_size))).all())
        return items, total
