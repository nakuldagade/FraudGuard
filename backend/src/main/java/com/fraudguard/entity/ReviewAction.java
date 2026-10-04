package com.fraudguard.entity;

// What an analyst can do to a transaction. Each action leads to one review state.
public enum ReviewAction {
    CONFIRM_FRAUD(ReviewState.CONFIRMED_FRAUD),
    MARK_LEGITIMATE(ReviewState.MARKED_LEGITIMATE),
    REQUEST_FURTHER_REVIEW(ReviewState.NEEDS_MORE_REVIEW);

    private final ReviewState resultingState;

    ReviewAction(ReviewState resultingState) {
        this.resultingState = resultingState;
    }

    public ReviewState toState() {
        return resultingState;
    }
}