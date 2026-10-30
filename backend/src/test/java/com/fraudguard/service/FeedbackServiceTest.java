package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.dto.FeedbackStatsDTO;
import com.fraudguard.dto.LabeledTransactionDTO;
import com.fraudguard.entity.AnalystReview;
import com.fraudguard.entity.ReviewAction;
import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.repository.AnalystReviewRepository;
import com.fraudguard.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Phase 9 label rules: newest review wins; fraud -> 1, legitimate -> 0, "further review" -> no label;
// rows without a usable decision snapshot are skipped; output is ordered by payment time.
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T12:00:00Z");
    private static final String SNAPSHOT = "{\"amount\":100,\"history_count\":3}";

    @Mock
    private AnalystReviewRepository reviews;
    @Mock
    private TransactionRepository transactions;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(reviews, transactions, new ObjectMapper());
    }

    @Test
    void confirmFraudIsLabel1AndMarkLegitimateIsLabel0() {
        UUID fraudTx = UUID.randomUUID();
        UUID legitTx = UUID.randomUUID();
        when(reviews.findLatestPerTransaction()).thenReturn(List.of(
                review(fraudTx, ReviewAction.CONFIRM_FRAUD, T0),
                review(legitTx, ReviewAction.MARK_LEGITIMATE, T0)));
        when(transactions.findAllById(any())).thenReturn(List.of(
                tx(fraudTx, T1, SNAPSHOT),
                tx(legitTx, T2, SNAPSHOT)));

        List<LabeledTransactionDTO> result = service.labeledTransactions();

        // ordered by occurredAt, so the fraud transaction (T1) comes first
        assertThat(result).extracting(LabeledTransactionDTO::label).containsExactly(1, 0);
        assertThat(result).extracting(LabeledTransactionDTO::transactionId).containsExactly(fraudTx, legitTx);
    }

    @Test
    void requestFurtherReviewIsNotALabel() {
        UUID id = UUID.randomUUID();
        when(reviews.findLatestPerTransaction())
                .thenReturn(List.of(review(id, ReviewAction.REQUEST_FURTHER_REVIEW, T0)));

        assertThat(service.labeledTransactions()).isEmpty();
    }

    @Test
    void newestReviewWinsRegardlessOfListOrder() {
        UUID id = UUID.randomUUID();
        // the newer review (legitimate) is listed FIRST on purpose: order must not matter
        when(reviews.findLatestPerTransaction()).thenReturn(List.of(
                review(id, ReviewAction.MARK_LEGITIMATE, T1),
                review(id, ReviewAction.CONFIRM_FRAUD, T0)));
        when(transactions.findAllById(any())).thenReturn(List.of(tx(id, T0, SNAPSHOT)));

        List<LabeledTransactionDTO> result = service.labeledTransactions();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isZero();
        assertThat(result.get(0).action()).isEqualTo(ReviewAction.MARK_LEGITIMATE);
    }

    @Test
    void laterRequestForFurtherReviewRemovesAnEarlierLabel() {
        UUID id = UUID.randomUUID();
        when(reviews.findLatestPerTransaction()).thenReturn(List.of(
                review(id, ReviewAction.CONFIRM_FRAUD, T0),
                review(id, ReviewAction.REQUEST_FURTHER_REVIEW, T1)));

        // the analyst's current verdict is "undecided", so there is no label
        assertThat(service.labeledTransactions()).isEmpty();
    }

    @Test
    void rowsWithoutAUsableSnapshotAreSkipped() {
        UUID good = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID blank = UUID.randomUUID();
        UUID corrupt = UUID.randomUUID();
        when(reviews.findLatestPerTransaction()).thenReturn(List.of(
                review(good, ReviewAction.CONFIRM_FRAUD, T0),
                review(missing, ReviewAction.CONFIRM_FRAUD, T0),
                review(blank, ReviewAction.CONFIRM_FRAUD, T0),
                review(corrupt, ReviewAction.CONFIRM_FRAUD, T0)));
        when(transactions.findAllById(any())).thenReturn(List.of(
                tx(good, T0, SNAPSHOT),
                tx(missing, T0, null),
                tx(blank, T0, "  "),
                tx(corrupt, T0, "{not json")));

        assertThat(service.labeledTransactions())
                .extracting(LabeledTransactionDTO::transactionId)
                .containsExactly(good);
    }

    @Test
    void examplesComeOutOldestPaymentFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(reviews.findLatestPerTransaction()).thenReturn(List.of(
                review(a, ReviewAction.CONFIRM_FRAUD, T0),
                review(b, ReviewAction.CONFIRM_FRAUD, T0),
                review(c, ReviewAction.CONFIRM_FRAUD, T0)));
        when(transactions.findAllById(any())).thenReturn(List.of(
                tx(a, T2, SNAPSHOT), tx(b, T0, SNAPSHOT), tx(c, T1, SNAPSHOT)));

        // a time-ordered train/validation/test split in the trainer relies on this ordering
        assertThat(service.labeledTransactions())
                .extracting(LabeledTransactionDTO::transactionId)
                .containsExactly(b, c, a);
    }

    @Test
    void statsCountTheLatestVerdictPerTransaction() {
        when(reviews.findLatestPerTransaction()).thenReturn(List.of(
                review(UUID.randomUUID(), ReviewAction.CONFIRM_FRAUD, T0),
                review(UUID.randomUUID(), ReviewAction.CONFIRM_FRAUD, T0),
                review(UUID.randomUUID(), ReviewAction.MARK_LEGITIMATE, T0),
                review(UUID.randomUUID(), ReviewAction.REQUEST_FURTHER_REVIEW, T0)));
        when(transactions.countByReviewState(ReviewState.UNREVIEWED)).thenReturn(7L);

        FeedbackStatsDTO stats = service.stats();

        assertThat(stats.confirmedFraud()).isEqualTo(2);
        assertThat(stats.markedLegitimate()).isEqualTo(1);
        assertThat(stats.furtherReviewRequested()).isEqualTo(1);
        assertThat(stats.unreviewed()).isEqualTo(7);
        assertThat(stats.usableLabels()).isEqualTo(3);      // fraud + legitimate only
    }

    // ---- helpers ----

    private static AnalystReview review(UUID transactionId, ReviewAction action, Instant createdAt) {
        AnalystReview r = new AnalystReview();
        r.setId(UUID.randomUUID());
        r.setTransactionId(transactionId);
        r.setAction(action);
        r.setReviewer("tester");
        r.setCreatedAt(createdAt);
        return r;
    }

    private static Transaction tx(UUID id, Instant occurredAt, String decisionInputs) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setOccurredAt(occurredAt);
        t.setAmount(new BigDecimal("100.00"));
        t.setStatus(TransactionStatus.REVIEW);
        t.setDecisionInputs(decisionInputs);
        return t;
    }
}
