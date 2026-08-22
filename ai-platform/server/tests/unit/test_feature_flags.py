import uuid

from app.models.entities import FeatureFlag
from app.services.feature_flags import evaluate_flag


def flag(**kwargs) -> FeatureFlag:
    item = FeatureFlag(key=kwargs.get('key', 'demo'))
    item.enabled = kwargs.get('enabled', False)
    item.rollout_percentage = kwargs.get('rollout_percentage', 0)
    return item


def test_a_user_override_wins_over_the_global_state():
    assert evaluate_flag(flag(enabled=True), uuid.uuid4(), override=False) is False
    assert evaluate_flag(flag(enabled=False), uuid.uuid4(), override=True) is True


def test_a_globally_enabled_flag_is_on_without_an_override():
    assert evaluate_flag(flag(enabled=True), uuid.uuid4()) is True


def test_rollout_bucketing_is_stable_for_a_user():
    user = uuid.uuid4()
    item = flag(rollout_percentage=50)
    assert evaluate_flag(item, user) == evaluate_flag(item, user)


def test_full_rollout_includes_everyone_and_zero_excludes_everyone():
    for _ in range(20):
        user = uuid.uuid4()
        assert evaluate_flag(flag(rollout_percentage=100), user) is True
        assert evaluate_flag(flag(rollout_percentage=0), user) is False


def test_rollout_needs_a_user_to_bucket_against():
    assert evaluate_flag(flag(rollout_percentage=100), None) is False
