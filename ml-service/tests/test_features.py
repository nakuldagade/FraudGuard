from app.features import FEATURE_NAMES, to_vector


def row(**overrides):
    base = dict(amount=100.0, hour_of_day=14, transaction_count_5m=1, transaction_count_1h=1,
                history_count=20, average_amount=100.0, amount_z_score=0.5,
                seconds_since_previous=3600, new_device=False, new_merchant=False, country_changed=False)
    base.update(overrides)
    return base


def named(vector):
    return dict(zip(FEATURE_NAMES, vector))


def test_vector_has_one_value_per_feature_name():
    assert len(to_vector(row())) == len(FEATURE_NAMES)


def test_missing_history_uses_neutral_values_not_nan():
    v = named(to_vector(row(history_count=0, average_amount=0.0, amount_z_score=None,
                            seconds_since_previous=None)))
    assert v["amount_to_average_ratio"] == 1.0     # no history: "as usual"
    assert v["amount_z_score"] == 0.0
    assert v["log_seconds_since_previous"] > 10     # "a day ago", not zero


def test_extreme_values_are_clipped():
    v = named(to_vector(row(amount=1_000_000.0, average_amount=1.0, amount_z_score=2619.9, history_count=5000)))
    assert v["amount_z_score"] == 50.0
    assert v["amount_to_average_ratio"] == 100.0
    assert v["history_count"] == 100.0


def test_flags_become_zero_or_one():
    v = named(to_vector(row(new_device=True, new_merchant=False, country_changed=True)))
    assert (v["new_device"], v["new_merchant"], v["country_changed"]) == (1.0, 0.0, 1.0)