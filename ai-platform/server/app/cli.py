import argparse
import asyncio
import getpass
import os
import uuid
from datetime import UTC, datetime

from sqlalchemy import delete, select, update

from app.db.session import get_engine, get_session
from app.models.entities import RefreshToken, Session, User
from app.repositories.users import UserRepository
from app.security.passwords import hash_password, validate_password_policy


async def _session():
    agen=get_session(); return agen, await agen.__anext__()


async def create_admin(args):
    email=(args.email or os.getenv("ADMIN_EMAIL") or input("Admin email: ")).strip().lower()
    password=args.password or os.getenv("ADMIN_INITIAL_PASSWORD") or getpass.getpass("Admin password: ")
    validate_password_policy(password)
    agen,db=await _session()
    try:
        repo=UserRepository(db); existing=await repo.by_email(email)
        if existing:
            existing.is_admin=True; existing.is_active=True; existing.password_hash=hash_password(password)
        else:
            await repo.create(email=email,password_hash=hash_password(password),is_admin=True,is_active=True)
        await db.commit(); print(f"Admin ready: {email}")
    finally: await agen.aclose()


async def health(args):
    from sqlalchemy import text
    try:
        async with get_engine().connect() as conn: await conn.execute(text("SELECT 1"))
        print("database: ok")
    except Exception as exc:
        print(f"database: failed: {exc}")
        raise SystemExit(1) from exc


async def list_users(args):
    agen,db=await _session()
    try:
        rows=(await db.scalars(select(User).order_by(User.created_at))).all()
        for u in rows: print(u.id,u.email,"admin" if u.is_admin else "user","active" if u.is_active else "disabled")
    finally: await agen.aclose()


async def revoke_sessions(args):
    user_id=uuid.UUID(args.user_id); now=datetime.now(UTC); agen,db=await _session()
    try:
        await db.execute(update(Session).where(Session.user_id==user_id,Session.revoked_at.is_(None)).values(revoked_at=now))
        await db.execute(update(RefreshToken).where(RefreshToken.user_id==user_id,RefreshToken.revoked_at.is_(None)).values(revoked_at=now,revoke_reason="cli"))
        await db.commit(); print("sessions revoked")
    finally: await agen.aclose()


async def cleanup(args):
    now=datetime.now(UTC); agen,db=await _session()
    try:
        result=await db.execute(delete(RefreshToken).where(RefreshToken.expires_at<now,RefreshToken.revoked_at.is_not(None)))
        await db.commit(); print(f"expired refresh tokens removed: {result.rowcount or 0}")
    finally: await agen.aclose()


def main():
    parser=argparse.ArgumentParser(prog="python -m app.cli")
    sub=parser.add_subparsers(dest="command",required=True)
    p=sub.add_parser("create-admin"); p.add_argument("--email"); p.add_argument("--password")
    sub.add_parser("health"); sub.add_parser("list-users")
    p=sub.add_parser("revoke-user-sessions"); p.add_argument("user_id")
    sub.add_parser("cleanup-expired-tokens")
    sub.add_parser("migrate")
    args=parser.parse_args()
    if args.command=="migrate":
        os.execvp("alembic",["alembic","upgrade","head"])
    funcs={"create-admin":create_admin,"health":health,"list-users":list_users,"revoke-user-sessions":revoke_sessions,"cleanup-expired-tokens":cleanup}
    asyncio.run(funcs[args.command](args))


if __name__=="__main__": main()
