from fastapi import APIRouter

from app.api.v1 import admin, ai, auth, devices, feature_flags, notifications, system, users, engineering

router = APIRouter(prefix="/api/v1")
router.include_router(auth.router, prefix="/auth", tags=["auth"])
router.include_router(users.router, prefix="/users", tags=["users"])
router.include_router(devices.router, prefix="/devices", tags=["devices"])
router.include_router(ai.router, prefix="/ai", tags=["ai"])
router.include_router(admin.router, prefix="/admin", tags=["admin"])
router.include_router(system.router, prefix="/system", tags=["system"])
router.include_router(notifications.router, prefix="/notifications", tags=["notifications"])
router.include_router(feature_flags.router, prefix="/feature-flags", tags=["feature-flags"])

router.include_router(engineering.router, prefix="/engineering", tags=["engineering"])
