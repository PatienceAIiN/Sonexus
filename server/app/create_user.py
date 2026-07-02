"""Create a portal user: python -m app.create_user <email> <password>

(The API contract has no signup endpoint, so users are provisioned via this CLI.)
"""
import asyncio
import sys

from .db import SessionLocal
from .models import User
from .security import hash_password


async def main(email: str, password: str) -> None:
    async with SessionLocal() as db:
        db.add(User(email=email, password_hash=hash_password(password)))
        await db.commit()
    print(f"Created user {email}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: python -m app.create_user <email> <password>")
    asyncio.run(main(sys.argv[1], sys.argv[2]))
