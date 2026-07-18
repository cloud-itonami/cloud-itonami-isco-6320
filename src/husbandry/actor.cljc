(ns husbandry.actor
  "HusbandryActor — the ISCO-08 6320 subsistence-livestock-farmers farm
  record-keeping/logistics coordination actor as a
  `langgraph.graph/state-graph` (ADR-2607121000 / CLAUDE.md Actors
  section). One graph run = one farm record-keeping/logistics
  operation request (intake -> advise -> govern -> decide -> commit/
  hold, with a human-approval interrupt for escalated proposals). No
  infinite internal loop; checkpointed per superstep so an interrupted
  run can resume after human sign-off. Modeled on cloud-itonami-
  isco-9332's cartage.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                             +-> :request-approval   (:escalate? true, interrupt-before)
                                             +-> :hold               (:hard? true)
  ```

  The unconditional invariant: the Farm Operations Advisor can never
  directly commit a record the HusbandryGovernor refuses — every
  commit-record! call is gated behind `:decide` — and NO commit path
  can ever finalize an animal-treatment/welfare/breeding decision or
  override the farmer's own judgment about their household's
  livestock (permanently out of scope, structurally absent from the
  op allowlist, see `husbandry.governor`). Every observation
  suggesting an animal-welfare, injury-risk, or livelihood-loss
  concern needs attention can ONLY reach a human via the always-
  escalating `:flag-welfare-concern` op — the robot's role ends at
  \"here is the feeding/roster/condition-check-in status\", never
  \"here is whether the animal needs treatment\" or \"here is whether
  the animal should be bred\". This actor coordinates farm record-
  keeping/logistics ONLY — it never handles the animals and never
  directly makes a treatment, welfare or breeding decision."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [husbandry.advisor :as advisor]
            [husbandry.governor :as governor]
            [husbandry.store :as store]))

(defn build-graph
  "Build a compiled HusbandryActor graph. `store` implements
  `husbandry.store/Store`. `advisor` implements
  `husbandry.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     {:disposition (cond
                                     (:hard? verdict) :hold
                                     (:escalate? verdict) :request-approval
                                     :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [proposal]}]
                     (let [record {:farmer-id (:farmer-id proposal)
                                    :holding-id (:holding-id proposal)
                                    :op (:op proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       (store/append-ledger! store {:disposition :commit :record record})
                       {:record record
                        :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (store/append-ledger! store {:disposition :hold :verdict verdict})
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread). This is a human acknowledging that the
  feeding/roster/condition-check-in status has been surfaced/
  coordinated as proposed — it is NEVER an approval of an animal-
  treatment/welfare/breeding decision, or an override of the farmer's
  own judgment about their household's livestock, since no such
  proposal can ever reach this point (see
  `husbandry.governor/closed-op-allowlist`)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
