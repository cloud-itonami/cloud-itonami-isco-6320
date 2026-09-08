(ns husbandry.governor
  "HusbandryGovernor — the independent safety/traceability layer named
  in this repository's README/business-model.md, gating every farm
  record-keeping/logistics-coordination operation an advisor may
  propose. The governor never dispatches hardware itself and NEVER
  lets a proposal exercise, simulate exercising, or propose exercising
  ANY animal-treatment/welfare/breeding decision, or ANY override of
  the farmer's own judgment about their household's livestock — every
  one of these is permanently out of scope for this actor, not merely
  gated behind escalation. This carries a livelihood-vulnerability
  dimension (livestock loss directly threatens household food
  security) AND an animal-welfare dimension AND standard animal-
  handling physical-safety hazards; this actor coordinates FARM
  RECORD-KEEPING/LOGISTICS ONLY — it never handles the animals and
  never makes treatment decisions itself. This mirrors the Wave4
  person-facing-service safety guardrail (ADR-2607152500): decisions
  directly touching an animal's treatment/welfare/breeding, or a
  household's own livestock judgment, always exclude the closed op
  allowlist and always escalate to the human farmer. Modeled on
  cloud-itonami-isco-9332's cartage.governor, with the same closed
  proposal-op allowlist + content-based scope-exclusion shape, adapted
  to this vertical's animal-treatment/welfare/breeding and household-
  livelihood guardrail.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. farmer provenance          — the proposing farmer/livestock-
                                holding record must be independently
                                registered AND verified before ANY
                                proposal can commit or escalate. Never
                                trusts the proposal's own claim of who
                                the farmer is.
    2. no-actuation                — proposal :effect must be :propose
                                (the governor never dispatches hardware
                                and never itself handles the animals or
                                makes a treatment decision; it only
                                gates what the advisor may commit).
    3. closed op allowlist        — the proposal's :op must be one of
                                the four ops this actor is scoped to
                                (`closed-op-allowlist` below). This is
                                the STRUCTURAL guarantee: no op that
                                resembles finalizing an animal-
                                treatment/welfare/breeding decision, or
                                overriding the farmer's own judgment
                                about their household's livestock,
                                exists anywhere in this allowlist —
                                such a proposal cannot even reach a
                                check, let alone pass one. Any :op
                                outside the allowlist is a HARD,
                                PERMANENT block.
    4. holding basis               — a proposal for `:log-work-record`,
                                `:schedule-farm-operation` or
                                `:coordinate-supply-order` must cite a
                                REGISTERED AND VERIFIED holding
                                matching the farmer's own holding
                                (`:unknown-holding` / `:holding-
                                unverified` / `:holding-mismatch`).
                                `:flag-welfare-concern` does NOT
                                require an existing holding (it is the
                                channel by which a brand-new holding,
                                or an urgent welfare/livelihood
                                concern with no holding on file yet, is
                                surfaced for human intake).
    5. work-record decision forbidden — `:log-work-record` is a
                                feeding-schedule/animal-condition-
                                check-in metadata record ONLY (work id,
                                feeding schedule, condition check-in,
                                timestamp). Any proposal carrying an
                                animal-treatment/welfare/breeding-
                                decision field (`log-record-forbidden-
                                keys` below — e.g. `:treatment-
                                decision`, `:welfare-disposition`,
                                `:breeding-decision`) is a HARD,
                                PERMANENT block — this actor never
                                records a treatment decision, a welfare
                                disposition or a breeding decision,
                                only metadata check-ins.
    6. farm-schedule override forbidden — `:schedule-farm-operation`
                                is household labor/task scheduling
                                logistics ONLY. Any proposal carrying a
                                treatment-directive or farmer-judgment-
                                override field (`schedule-forbidden-
                                keys` below — e.g. `:treatment-
                                directive`, `:farmer-judgment-
                                override`, `:breeding-directive`) is a
                                HARD, PERMANENT block — this actor
                                never overrides the farmer's own
                                judgment about their household's
                                livestock, it only schedules which
                                household member/task is assigned to
                                which farm-operation slot in advance.
    7. scope exclusion             — independent, DEFENSE-IN-DEPTH
                                layer on top of #3/#5/#6: even for an
                                otherwise-allowed op, any proposal
                                whose free text (`:rationale` or
                                `:note`) names a finalization/execution
                                ACTION for an animal-treatment/welfare/
                                breeding decision, or an override of
                                the farmer's own judgment about their
                                household's livestock
                                (`scope-excluded-terms` below) is a
                                HARD, PERMANENT block, evaluated
                                unconditionally on content. This actor
                                never exercises treatment, welfare,
                                breeding, or farmer-override authority
                                — it only documents farm records and
                                coordinates logistics.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off — these
  are :high/:safety-critical regardless of confidence):
    8. :op :flag-welfare-concern (surfacing an animal-welfare, injury-
                                risk, or livelihood-loss concern that
                                needs human review — ALWAYS requires
                                human review; it is never auto-resolved
                                and never appears in any phase's
                                auto-commit set; this is the ONLY path
                                by which such a concern may be
                                surfaced, and the robot's role ends at
                                \"here is the feeding/roster/condition-
                                check-in status\" — never \"here is
                                whether the animal needs treatment\" or
                                \"here is whether the animal should be
                                bred\").
    9. an above-threshold :coordinate-supply-order (feed/supplies
                                procurement above `supply-cost-
                                escalation-threshold` always needs
                                human sign-off, regardless of
                                confidence — this is an escalation, NOT
                                a hard block, since an over-budget
                                supply order is not itself unsafe).
    10. low confidence (< `confidence-floor`)."
  (:require [kotoba.lang.text :as str]
            [husbandry.store :as store]))

(def confidence-floor 0.6)

;; Feed/supplies procurement orders at or below this estimated cost
;; may be auto-commit-eligible (subject to confidence); above it,
;; ALWAYS escalates to a human regardless of confidence. Subsistence-
;; livestock households operate on tighter margins than a commercial
;; depot, so this threshold is set lower than cloud-itonami-isco-9332's
;; maintenance-cost-escalation-threshold.
(def supply-cost-escalation-threshold 300)

;; The closed proposal-op allowlist. This governor NEVER allows any op
;; outside this set to commit or even escalate — an op outside this
;; set is a HARD, permanent block (see `hard-violations`
;; :op-not-allowed below), not merely un-auto-committable. This is a
;; farm record-keeping/logistics coordination robot ONLY: it has NO
;; op, anywhere in this allowlist, that resembles finalizing an
;; animal-treatment/welfare/breeding decision, or overriding the
;; farmer's own judgment about their household's livestock. Those
;; capabilities are structurally absent, not gated.
(def closed-op-allowlist
  #{:log-work-record :schedule-farm-operation
    :flag-welfare-concern :coordinate-supply-order})

;; :flag-welfare-concern always escalates to a human — never
;; auto-commit-eligible at any phase. It is the ONLY channel through
;; which an animal-welfare/injury-risk/livelihood-loss concern may be
;; surfaced.
(def ^:private always-escalate-ops #{:flag-welfare-concern})

;; Ops that outside observers might expect a "subsistence livestock
;; farm actor" to have — named here explicitly (in addition to the
;; closed-allowlist check above) so the exclusion reads as an
;; intentional, documented scope boundary rather than an incidental
;; unknown op. None of these are ever defined as a real op anywhere in
;; this codebase; they exist ONLY as negative-test fixtures proving
;; `closed-op-allowlist` rejects them.
(def scope-excluded-ops
  #{:determine-animal-fitness-for-breeding :decide-animal-fitness-for-breeding
    :authorize-veterinary-treatment :order-veterinary-treatment
    :administer-veterinary-treatment
    :decide-animal-welfare-disposition :determine-animal-welfare-disposition
    :finalize-breeding-decision :determine-breeding-decision
    :authorize-livestock-cull :order-livestock-cull
    :override-farmer-livestock-judgment :override-household-livestock-judgment})

;; log-work-record is a feeding-schedule/animal-condition-check-in
;; metadata record ONLY. A proposal carrying any of these keys is
;; smuggling an animal-treatment/welfare/breeding decision into what
;; must remain a pure metadata check-in.
(def log-record-forbidden-keys
  #{:treatment-decision :veterinary-treatment-order :welfare-disposition
    :breeding-decision :breeding-fitness-decision :cull-decision})

;; schedule-farm-operation is household labor/task-ASSIGNMENT
;; scheduling logistics ONLY (deciding in advance which household
;; member/task is assigned to which farm-operation slot). A proposal
;; carrying any of these keys is smuggling a treatment directive, a
;; breeding directive, or an override of the farmer's own livestock
;; judgment into what must remain pure advance scheduling.
(def schedule-forbidden-keys
  #{:treatment-directive :farmer-judgment-override :livestock-judgment-override
    :breeding-directive :cull-directive :welfare-disposition})

;; Scope-exclusion terms, phrased as the FINALIZATION/EXECUTION ACTION
;; (never a bare noun like "breeding" or "welfare" alone) — a known
;; self-tripping bug class in this fleet: a bare-noun term list can
;; accidentally match inside the mock advisor's own default rationale
;; text for a legitimate, allowed proposal (this actor's own op is
;; literally named `:flag-welfare-concern`, so a bare "welfare" term
;; would self-trip on every single legitimate welfare-flag proposal),
;; causing the actor to self-block on its own happy path. This
;; advisor's default rationale template is "documented <op> for
;; holding <id>", which never contains any of these full action
;; phrases. See `husbandry.governor-test/
;; default-mock-advisor-proposals-never-self-trip-scope-exclusion`.
(def scope-excluded-terms
  ["determined the animal's fitness for breeding" "determine the animal's fitness for breeding"
   "decided the animal's fitness for breeding" "decide the animal's fitness for breeding"
   "authorized the veterinary treatment" "authorize the veterinary treatment"
   "ordered the veterinary treatment" "order the veterinary treatment"
   "administered the veterinary treatment" "administer the veterinary treatment"
   "decided the animal's welfare disposition" "decide the animal's welfare disposition"
   "determined the animal's welfare disposition" "determine the animal's welfare disposition"
   "finalized the breeding decision" "finalize the breeding decision"
   "ordered the livestock cull" "order the livestock cull"
   "authorized the livestock cull" "authorize the livestock cull"
   "overrode the farmer's livestock judgment" "override the farmer's livestock judgment"
   "overrode the farmer's household livestock judgment" "override the farmer's household livestock judgment"
   "overrode the farmer's own livestock judgment" "override the farmer's own livestock judgment"
   "動物の繁殖適性を判定した" "動物の福祉処遇を決定した" "獣医治療を指示した"
   "淘汰を指示した" "農家の家畜判断を上書きした"])

(defn out-of-scope?
  "True if any free-text field on `proposal` (:rationale or :note)
  contains a scope-excluded finalization/execution phrase for an
  animal-treatment/welfare/breeding decision, or an override of the
  farmer's own judgment about their household's livestock."
  [proposal]
  (let [text (str (:rationale proposal) " " (:note proposal))]
    (boolean (some #(str/includes? text %) scope-excluded-terms))))

(defn- forbidden-keys-present [proposal forbidden-keys]
  (seq (filter #(contains? proposal %) forbidden-keys)))

(def ^:private holding-required-ops
  #{:log-work-record :schedule-farm-operation :coordinate-supply-order})

(defn- hard-violations [{:keys [proposal]} farmer-record holding-record]
  (let [{:keys [op holding-id]} proposal
        needs-holding? (contains? holding-required-ops op)]
    (cond-> []
      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は動物の治療/福祉/繁殖判断を直接実行しない）"})

      (not (contains? closed-op-allowlist op))
      (conj {:rule :op-not-allowed
             :detail "closed allowlist 外の op（動物の治療/福祉/繁殖判断・農家の家畜判断の上書きを含む一切の確定は許可されない）"})

      (nil? farmer-record)
      (conj {:rule :unknown-farmer :detail "未登録 farmer への提案は不可"})

      (and farmer-record (not (:verified? farmer-record)))
      (conj {:rule :farmer-unverified :detail "未検証 farmer への提案は不可（登録のみでは不十分）"})

      (and needs-holding? (nil? holding-id))
      (conj {:rule :missing-holding-id :detail "この op には holding-id が必須"})

      (and needs-holding? holding-id (nil? holding-record))
      (conj {:rule :unknown-holding :detail "未登録 holding への提案は不可"})

      (and needs-holding? holding-record (not (:verified? holding-record)))
      (conj {:rule :holding-unverified :detail "未検証 holding への提案は不可（登録のみでは不十分）"})

      (and needs-holding? holding-record farmer-record
           (not= (:holding-id holding-record) (:holding-id farmer-record)))
      (conj {:rule :holding-mismatch :detail "holding が farmer の所属と別 holding のもの"})

      (and (= :log-work-record op) (seq (forbidden-keys-present proposal log-record-forbidden-keys)))
      (conj {:rule :work-record-decision-forbidden
             :detail "log-work-record は feeding-schedule/動物状態チェックインのメタデータ記録のみ — 治療判断・福祉処遇・繁殖判断の記録は永久に禁止"})

      (and (= :schedule-farm-operation op) (seq (forbidden-keys-present proposal schedule-forbidden-keys)))
      (conj {:rule :farm-schedule-override-forbidden
             :detail "schedule-farm-operation は事前の家事/作業割当スケジューリングのみ — 治療指示・農家の家畜判断の上書きは永久に禁止"})

      (out-of-scope? proposal)
      (conj {:rule :scope-excluded
             :detail "動物の治療/福祉/繁殖判断・農家の家畜判断の上書きを直接確定する提案は恒久的に許可されない（このactorは文書化と farm record-keeping/logistics 調整のみを行う）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `husbandry.store/Store`. Pure — never mutates
  the store, never finalizes an animal-treatment/welfare/breeding
  decision, never overrides the farmer's own judgment about their
  household's livestock."
  [_request _context proposal store]
  (let [farmer-record (some->> (:farmer-id proposal) (store/farmer store))
        holding-record (some->> (:holding-id proposal) (store/holding store))
        hard (hard-violations {:proposal proposal} farmer-record holding-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops (:op proposal))
        over-threshold-supply-order?
        (and (= :coordinate-supply-order (:op proposal))
             (number? (:cost proposal))
             (> (:cost proposal) supply-cost-escalation-threshold))]
    {:ok? (and (not hard?) (not low?) (not always-risky?) (not over-threshold-supply-order?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky? over-threshold-supply-order?))}))
