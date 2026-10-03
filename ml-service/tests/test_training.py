"""Checks that the training-side feature computation matches the live Redis script
(backend/src/main/resources/redis/record_transaction.lua) and that evaluation is leak-free."""
import numpy as np
import pytest

from training.train import AccountState, build_dataset, chronological_split

MINUTE = 60


def observe(state, ts, amount, device="d", merchant="m", country="IN"):
    return state.observe(ts, amount, device, merchant, country)


def test_sliding_windows_match_the_redis_script():
    s = AccountState()
    t0 = 1_000_000
    observe(s, t0, 10)
    observe(s, t0 + 10 * MINUTE, 20)
    observe(s, t0 + 58 * MINUTE, 30)
    last = observe(s, t0 + 62 * MINUTE, 40)
    assert last["transaction_count_1h"] == 3     # the first transaction has left the 1-hour window
    assert last["transaction_count_5m"] == 2


def test_amount_baseline_needs_history_like_the_redis_script():
    s = AccountState()
    for i, amount in enumerate([100, 102, 98, 101]):
        observe(s, i * MINUTE, amount)
    fifth = observe(s, 4 * MINUTE, 99)
    assert fifth["history_count"] == 4 and fifth["amount_z_score"] is None
    outlier = observe(s, 5 * MINUTE, 500)
    assert outlier["history_count"] == 5
    assert outlier["average_amount"] == pytest.approx(100.0)
    assert outlier["amount_z_score"] == pytest.approx(252.98, abs=0.1)


def test_flags_and_gaps_match_the_redis_script():
    s = AccountState()
    first = observe(s, 0, 10, device="A", merchant="m1", country="IN")
    assert first["new_device"] and first["new_merchant"] and first["seconds_since_previous"] is None
    second = observe(s, 90, 10, device="A", merchant="m2", country="US")
    assert not second["new_device"] and second["new_merchant"]
    assert second["seconds_since_previous"] == 90 and second["country_changed"]


def test_chronological_split_never_mixes_past_and_future():
    X, y, ts = build_dataset(n_accounts=40, days=30, seed=1)
    train, val, test = chronological_split(len(y))
    assert ts[train].max() <= ts[val].min()
    assert ts[val].max() <= ts[test].min()
    assert train.stop == val.start and val.stop == test.start and test.stop == len(y)


def test_dataset_is_reproducible_and_has_both_classes():
    a = build_dataset(n_accounts=40, days=30, seed=7)
    b = build_dataset(n_accounts=40, days=30, seed=7)
    assert np.array_equal(a[0], b[0]) and np.array_equal(a[1], b[1])
    assert 0 < a[1].sum() < len(a[1])