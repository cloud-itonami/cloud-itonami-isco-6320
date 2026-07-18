(ns husbandry.store
  "SSoT for the ISCO-08 6320 subsistence-livestock-farmers farm record-
  keeping/logistics coordination actor (itonami actor pattern,
  ADR-2607121000 / CLAUDE.md Actors section; README's 'Robotics
  premise' — a farm-logistics robot performs feeding-schedule/animal-
  condition-check-in data entry, household labor/task scheduling, and
  feed/supplies procurement coordination under this advisor/governor
  pair, which never dispatches hardware itself, never handles the
  animals itself, and NEVER exercises, simulates exercising, or
  proposes exercising ANY animal-treatment/welfare/breeding decision,
  and never overrides the farmer's own judgment about their
  household's livestock — every one of those capabilities is a
  permanently out-of-scope, structurally absent op). Modeled on
  cloud-itonami-isco-9332's cartage.store, itself modeled on
  cloud-itonami-isco-5411's firestation.store.

  Domain:

    farmer  — a registered subsistence-livestock-farming household
              member/holder {:farmer-id :name :holding-id :verified?
              boolean}. Independently registered/verified identity,
              never trusted from the proposal alone (\"farmer/
              livestock-holding record must be independently verified/
              registered before any action\"). This actor never
              determines this farmer's animal-treatment, welfare or
              breeding decisions — it only logs, schedules and flags
              administrative/logistics records on the farmer's behalf.
    holding — a registered subsistence-livestock holding/smallholding
              {:holding-id :name :max-supply-cost number :verified?
              boolean}. Independently registered/verified, never
              trusted from the proposal alone. `:max-supply-cost` is
              the registered per-holding ceiling a proposed
              `:coordinate-supply-order` cost above which always
              escalates to a human — NOT a hard block, an over-budget
              supply order just needs sign-off, it is not itself
              unsafe.
    record  — a committed operating record (feeding-schedule/animal-
              condition-check-in log entry, household labor/task
              scheduling proposal, welfare-concern flag, or feed/
              supplies procurement coordination proposal) — written
              ONLY via commit-record!. A committed record is NEVER an
              animal-treatment/welfare/breeding decision, and never an
              override of the farmer's own judgment about their
              household's livestock — this actor documents and
              coordinates farm record-keeping/logistics, it never
              handles the animals or makes a treatment decision
              itself.
    ledger  — append-only audit trail, commit or hold.")

(defprotocol Store
  (farmer [s farmer-id])
  (holding [s holding-id])
  (records-of [s holding-id])
  (ledger [s])
  (register-farmer! [s f])
  (register-holding! [s h])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (farmer [_ farmer-id] (get-in @a [:farmers farmer-id]))
  (holding [_ holding-id] (get-in @a [:holdings holding-id]))
  (records-of [_ holding-id] (filter #(= holding-id (:holding-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-farmer! [s f]
    (swap! a assoc-in [:farmers (:farmer-id f)] f) s)
  (register-holding! [s h]
    (swap! a assoc-in [:holdings (:holding-id h)] h) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:farmers {} :holdings {} :records [] :ledger []}
                                   seed)))))
