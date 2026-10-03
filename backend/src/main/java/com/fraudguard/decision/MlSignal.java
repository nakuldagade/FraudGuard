package com.fraudguard.decision;

// How the policy treated the ML score for one transaction.
public enum MlSignal {
    UNAVAILABLE,            // the ML service gave no answer (down, slow, disabled)
    IGNORED_NEW_ACCOUNT,    // answered, but the account has too little history to trust the score
    NORMAL,                 // below the elevated level
    ELEVATED,               // at or above mlReviewAt
    VERY_HIGH               // at or above mlVeryHighAt
}