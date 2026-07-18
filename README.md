# cloud-itonami-isco-6320

Open Occupation Blueprint for **ISCO-08 6320**: Subsistence Livestock
Farmers.

This repository designs a forkable OSS business for a subsistence-
livestock-farm record-keeping and logistics coordination practice: a
farm-logistics robot manages feeding-schedule/animal-condition-check-in
records, household labor/task scheduling, and feed/supplies procurement
coordination under a governor-gated actor — and structurally **never**
finalizes an animal-treatment/welfare/breeding decision, or overrides
the farmer's own judgment about their household's livestock.

## This actor has no animal-treatment/welfare/breeding or farmer-override authority

Subsistence Livestock Farmers raise animals primarily for household
consumption — this carries a **livelihood-vulnerability dimension**
(livestock loss directly threatens household food security), an
**animal-welfare dimension**, and standard animal-handling
**physical-safety hazards**. **This actor is a farm record-keeping/
logistics coordination robot ONLY.** It never handles the animals and
never directly makes a treatment, welfare or breeding decision. It has
NO op, anywhere in its allowlist, that resembles finalizing an animal-
treatment/welfare/breeding decision, or overriding the farmer's own
judgment about their household's livestock. These are **structurally
absent from the closed op-allowlist entirely**, not merely gated
behind escalation — under any circumstance, at any confidence level,
in any phase. Any observation the robot logs that suggests an animal-
welfare, injury-risk, or livelihood-loss concern is surfaced ONLY via
an always-escalating `:flag-welfare-concern` op that a human reviews
and acts on entirely themselves. This mirrors the Wave4 person-facing-
service safety guardrail (ADR-2607152500): decisions directly touching
an animal's treatment/welfare/breeding, or a household's own livestock
judgment, always exclude the closed op allowlist and always escalate.
This actor's role ends at "here is the feeding/roster/condition-
check-in status" — it has zero authority over treatment, welfare, or
breeding decisions, which remain entirely with the human farmer, at
all times, with zero exception.

**Maturity: `:implemented`.** `src/husbandry/` implements the
`HusbandryActor` as a `langgraph.graph/state-graph` (`husbandry.actor`)
wired to a `Farm Operations Advisor` (`husbandry.advisor`) and an
independent `HusbandryGovernor` (`husbandry.governor`), following the
itonami actor pattern (ADR-2607121000): `:intake -> :advise -> :govern
-> :decide -+-> :commit (:ok?) +-> :request-approval (:escalate?,
human-in-the-loop interrupt) +-> :hold (:hard?)`. Run `clojure -M:test`
for the current test count.

HARD invariants (always hold, never overridable): farmer provenance (a
proposal must resolve to an independently registered AND verified
farmer/livestock-holding record), a closed four-op proposal allowlist
(any op outside it — including anything that would finalize an
animal-treatment/welfare/breeding decision, or override the farmer's
own judgment about their household's livestock — is a permanent HARD
block, because no such op exists in the allowlist to begin with),
no-actuation (`:effect` must be `:propose`), a registered-and-verified
holding basis (for the three ops that reference one), a work-record-
decision-forbidden check (`:log-work-record` may only carry feeding-
schedule/animal-condition-check-in metadata, never a treatment,
welfare, or breeding decision), a farm-schedule-override-forbidden
check (`:schedule-farm-operation` may only carry household labor/task
scheduling logistics, never a treatment directive or a farmer-
judgment override), and a content-based scope-exclusion check: any
proposal whose free text names a finalization/execution action for an
animal-treatment/welfare/breeding decision, or an override of the
farmer's own judgment about their household's livestock, is a
permanent HARD block, independent of and in addition to the
op-allowlist check. This actor **never** exercises, simulates
exercising, or proposes exercising any animal-treatment/welfare/
breeding decision, or any override of the farmer's own judgment about
their household's livestock — it only documents farm records and
coordinates logistics.

Always-escalate (human sign-off regardless of confidence, mapping this
repo's Trust Controls in
[`docs/business-model.md`](docs/business-model.md)):
`:flag-welfare-concern` (surfacing an animal-welfare, injury-risk, or
livelihood-loss concern that needs human review — always requires
human review; never auto-resolved, never in any phase's auto-commit
set — this is the ONLY channel by which such a concern may be
surfaced) and any `:coordinate-supply-order` above the registered
per-holding cost threshold.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical/administrative domain work**. Here a farm-
logistics robot performs feeding-schedule/animal-condition-check-in
data entry, household labor/task scheduling, and feed/supplies
procurement coordination under an actor that proposes actions and an
independent **HusbandryGovernor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such
as flagging a welfare concern, or an above-threshold supply order)
require human sign-off — and no action in this actor's closed op
allowlist can ever finalize an animal-treatment/welfare/breeding
decision, or override the farmer's own judgment about their
household's livestock. This actor coordinates FARM RECORD-KEEPING/
LOGISTICS ONLY — it never handles the animals and never makes
treatment decisions itself.

## Core Contract

```text
farmer intake queue + holding roster directory + supply policy
        |
        v
Farm Operations Advisor -> HusbandryGovernor -> log record/coordinate, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses,
finalize an animal-treatment/welfare/breeding decision, override the
farmer's own judgment about their household's livestock, suppress an
operating record, or disclose sensitive data without governor approval
and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `6320`). Required capabilities:

- :robotics
- :identity
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
