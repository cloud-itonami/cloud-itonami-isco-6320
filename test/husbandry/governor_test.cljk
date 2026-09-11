(ns husbandry.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [husbandry.store :as store]
            [husbandry.advisor :as advisor]
            [husbandry.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-farmer! st {:farmer-id "F-1" :name "Farmer Kobo"
                                :holding-id "holding-9" :verified? true})
    (store/register-holding! st {:holding-id "holding-9"
                                 :max-supply-cost 300 :verified? true})
    st))

(defn- log-op []
  {:op :log-work-record :effect :propose :farmer-id "F-1" :holding-id "holding-9"
   :work-id "W-1" :feeding-schedule :morning-and-evening
   :condition-checkin :serviceable :timestamp "2026-07-14T10:00:00Z" :stake :low :confidence 0.9
   :rationale "documented log-work-record for holding holding-9"})

(defn- schedule-op []
  {:op :schedule-farm-operation :effect :propose :farmer-id "F-1" :holding-id "holding-9"
   :task-id "T-12" :proposed-time "2026-07-20T09:00:00Z"
   :assignee "household-member-2" :stake :low :confidence 0.9
   :rationale "documented schedule-farm-operation for holding holding-9"})

(defn- flag-op
  ([] (flag-op nil))
  ([holding-id]
   {:op :flag-welfare-concern :effect :propose :farmer-id "F-1" :holding-id holding-id
    :reason :animal-welfare :note "animal showing signs of injury, recommend holding review"
    :stake :low :confidence 0.9
    :rationale "documented flag-welfare-concern for holding (no holding yet — new-holding intake)"}))

(defn- supply-op [cost]
  {:op :coordinate-supply-order :effect :propose :farmer-id "F-1" :holding-id "holding-9"
   :item "mineral feed supplement" :cost cost :vendor "HoldingSupplyCo" :stake :low :confidence 0.9
   :rationale "documented coordinate-supply-order for holding holding-9"})

(def ^:private req {})

;; --- happy path -----------------------------------------------------

(deftest ok-well-formed-log-entry
  (let [st (fresh-store)
        v (governor/check req {} (log-op) st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest ok-well-formed-farm-operation-scheduling
  (let [st (fresh-store)
        v (governor/check req {} (schedule-op) st)]
    (is (:ok? v))))

(deftest ok-at-or-below-threshold-supply-order
  (let [st (fresh-store)
        v (governor/check req {} (supply-op 150) st)]
    (is (:ok? v))))

(deftest ok-at-exact-supply-cost-threshold-boundary
  (testing "the supply-cost escalation threshold is inclusive (exactly-at-threshold does not escalate)"
    (let [st (fresh-store)
          v (governor/check req {} (supply-op governor/supply-cost-escalation-threshold) st)]
      (is (:ok? v))
      (is (not (:escalate? v))))))

;; --- farmer provenance ----------------------------------------------

(deftest hard-on-unregistered-farmer
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :farmer-id "ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-farmer (:rule %)) (:violations v)))))

(deftest hard-on-unverified-farmer
  (let [st (fresh-store)]
    (store/register-farmer! st {:farmer-id "F-2" :name "Unverified"
                                :holding-id "holding-9" :verified? false})
    (let [v (governor/check req {} (assoc (log-op) :farmer-id "F-2") st)]
      (is (:hard? v))
      (is (some #(= :farmer-unverified (:rule %)) (:violations v))))))

;; --- holding provenance ---------------------------------------------

(deftest hard-on-missing-holding-id
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :holding-id nil) st)]
    (is (:hard? v))
    (is (some #(= :missing-holding-id (:rule %)) (:violations v)))))

(deftest hard-on-unknown-holding
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :holding-id "holding-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-holding (:rule %)) (:violations v)))))

(deftest hard-on-unverified-holding
  (let [st (fresh-store)]
    (store/register-holding! st {:holding-id "holding-2" :max-supply-cost 300 :verified? false})
    (let [v (governor/check req {} (assoc (log-op) :holding-id "holding-2") st)]
      (is (:hard? v))
      (is (some #(= :holding-unverified (:rule %)) (:violations v))))))

(deftest hard-on-holding-mismatch
  (let [st (fresh-store)]
    (store/register-holding! st {:holding-id "holding-3" :max-supply-cost 300 :verified? true})
    (let [v (governor/check req {} (assoc (log-op) :holding-id "holding-3") st)]
      (is (:hard? v))
      (is (some #(= :holding-mismatch (:rule %)) (:violations v))))))

(deftest flag-welfare-concern-does-not-require-existing-holding
  (testing "flag-welfare-concern is the channel by which a brand-new holding, or an urgent concern with no holding
            on file yet, is surfaced for human intake"
    (let [st (fresh-store)
          v (governor/check req {} (flag-op nil) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

;; --- no-actuation / closed allowlist ----------------------------------

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-determine-animal-fitness-for-breeding
  (testing "no path through this actor can finalize an animal-breeding decision — no such op exists in the
            allowlist to begin with; this asserts the governor also rejects one forged onto a proposal"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :op :determine-animal-fitness-for-breeding) st)]
      (is (:hard? v))
      (is (some #(= :op-not-allowed (:rule %)) (:violations v))))))

(deftest hard-on-op-not-allowed-authorize-veterinary-treatment
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :authorize-veterinary-treatment) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-override-farmer-livestock-judgment
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :override-farmer-livestock-judgment) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest hard-on-op-not-allowed-authorize-livestock-cull
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :op :authorize-livestock-cull) st)]
    (is (:hard? v))
    (is (some #(= :op-not-allowed (:rule %)) (:violations v)))))

(deftest every-scope-excluded-op-name-is-rejected
  (testing "every explicitly named scope-excluded op fixture is a hard, permanent block"
    (let [st (fresh-store)]
      (doseq [op governor/scope-excluded-ops]
        (let [v (governor/check req {} (assoc (log-op) :op op) st)]
          (is (:hard? v) (str "op " op " was not hard-blocked"))
          (is (some #(= :op-not-allowed (:rule %)) (:violations v))
              (str "op " op " did not trip :op-not-allowed")))))))

;; --- work-record decision / farm-schedule override forbidden -------

(deftest hard-on-work-record-decision-forbidden-treatment-decision-key
  (testing "log-work-record is a feeding-schedule/animal-condition-check-in metadata record only — treatment
            decisions are forbidden"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :treatment-decision "administer rest day") st)]
      (is (:hard? v))
      (is (some #(= :work-record-decision-forbidden (:rule %)) (:violations v))))))

(deftest hard-on-work-record-decision-forbidden-welfare-disposition-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :welfare-disposition :requires-treatment) st)]
    (is (:hard? v))
    (is (some #(= :work-record-decision-forbidden (:rule %)) (:violations v)))))

(deftest hard-on-work-record-decision-forbidden-breeding-decision-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :breeding-decision :approved) st)]
    (is (:hard? v))
    (is (some #(= :work-record-decision-forbidden (:rule %)) (:violations v)))))

(deftest hard-on-farm-schedule-override-forbidden-treatment-directive-key
  (testing "schedule-farm-operation never carries a treatment directive"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (schedule-op) :treatment-directive "administer supplement") st)]
      (is (:hard? v))
      (is (some #(= :farm-schedule-override-forbidden (:rule %)) (:violations v))))))

(deftest hard-on-farm-schedule-override-forbidden-farmer-judgment-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (schedule-op) :farmer-judgment-override "proceed despite farmer hesitation") st)]
    (is (:hard? v))
    (is (some #(= :farm-schedule-override-forbidden (:rule %)) (:violations v)))))

(deftest hard-on-farm-schedule-override-forbidden-breeding-directive-key
  (let [st (fresh-store)
        v (governor/check req {} (assoc (schedule-op) :breeding-directive "proceed with pairing") st)]
    (is (:hard? v))
    (is (some #(= :farm-schedule-override-forbidden (:rule %)) (:violations v)))))

;; --- scope-excluded rationale (defense-in-depth) -----------------------

(deftest hard-on-scope-excluded-breeding-finalization-rationale
  (testing "a proposal on an otherwise-allowed op whose rationale names a finalization action for a breeding
            decision is a permanent HARD block, independent of the op-allowlist check"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :rationale "logged the check-in in order to finalize the breeding decision") st)]
      (is (:hard? v))
      (is (some #(= :scope-excluded (:rule %)) (:violations v))))))

(deftest hard-on-scope-excluded-farmer-judgment-override-rationale
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :rationale "logged the check-in to override the farmer's livestock judgment") st)]
    (is (:hard? v))
    (is (some #(= :scope-excluded (:rule %)) (:violations v)))))

(deftest hard-on-scope-excluded-animal-fitness-rationale
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :rationale "logged the check-in to determine the animal's fitness for breeding") st)]
    (is (:hard? v))
    (is (some #(= :scope-excluded (:rule %)) (:violations v)))))

(deftest hard-on-scope-excluded-veterinary-treatment-rationale
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :rationale "logged the check-in to order the veterinary treatment") st)]
    (is (:hard? v))
    (is (some #(= :scope-excluded (:rule %)) (:violations v)))))

(deftest hard-on-scope-excluded-note-field
  (testing "the scope-exclusion check also inspects :note (used by flag-welfare-concern)"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (flag-op "holding-9") :note "recommend we order the livestock cull now") st)]
      (is (:hard? v))
      (is (some #(= :scope-excluded (:rule %)) (:violations v))))))

;; --- escalation ---------------------------------------------------------

(deftest always-escalates-flag-welfare-concern-even-at-high-confidence
  (testing "surfacing a welfare/livelihood-loss concern always requires human review"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (flag-op "holding-9") :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-above-threshold-supply-order
  (testing "a feed/supplies order above the cost threshold always needs human sign-off"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (supply-op (+ governor/supply-cost-escalation-threshold 1))
                                          :confidence 0.99)
                            st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

;; --- fleet-known self-trip regression -----------------------------------

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own rationale text for every op in the closed allowlist never contains a
            scope-excluded finalization/execution phrase for an animal-treatment/welfare/breeding decision, or an
            override of the farmer's own judgment about their household's livestock -- crucially,
            :flag-welfare-concern's own op name contains the bare noun \"welfare\", so this asserts the term list
            is phrased as full finalization/execution actions rather than bare nouns"
    (let [st (fresh-store)
          adv (advisor/mock-advisor)
          requests [{:op :log-work-record :farmer-id "F-1" :holding-id "holding-9" :stake :low
                     :work-id "W-1" :feeding-schedule :morning-and-evening
                     :condition-checkin :serviceable :timestamp "2026-07-14T10:00:00Z"}
                    {:op :schedule-farm-operation :farmer-id "F-1" :holding-id "holding-9" :stake :low
                     :task-id "T-12" :proposed-time "2026-07-20T09:00:00Z" :assignee "household-member-2"}
                    {:op :flag-welfare-concern :farmer-id "F-1" :holding-id "holding-9" :stake :low
                     :reason :animal-welfare :note "animal showing signs of injury, recommend holding review"}
                    {:op :flag-welfare-concern :farmer-id "F-1" :holding-id nil :stake :low
                     :reason :livelihood-loss :note "new holding intake needs equipment audit"}
                    {:op :coordinate-supply-order :farmer-id "F-1" :holding-id "holding-9" :stake :low
                     :item "mineral feed supplement" :cost 40 :vendor "HoldingSupplyCo"}]]
      (doseq [req' requests]
        (let [proposal (advisor/-advise adv st req')]
          (is (false? (governor/out-of-scope? proposal))
              (str "op " (:op req') " self-tripped scope-exclusion: " (:rationale proposal)))
          (let [v (governor/check {} {} proposal st)]
            (is (not (contains? (set (map :rule (:violations v))) :scope-excluded))
                (str "op " (:op req') " tripped :scope-excluded in governor/check"))
            (is (not (contains? (set (map :rule (:violations v))) :op-not-allowed))
                (str "op " (:op req') " tripped :op-not-allowed in governor/check"))))))))
