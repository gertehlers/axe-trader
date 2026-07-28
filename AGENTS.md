## Model routing for subagent-driven development

Classify every task before dispatch as mechanical, judgment, or architecture.
Always set an explicit provider and model; never inherit the controller model.

| Role | OpenAI | Claude | Use for |
|---|---|---|---|
| Worker | Luna | Haiku | Exact, isolated, repeatable edits with explicit acceptance tests |
| Lead / normal implementer | Terra | Sonnet | Planning, repo exploration, non-trivial coding, integration, normal review |
| Escalation / final reviewer | Sol | Opus | Architecture, hard debugging, high-risk changes, final branch review |
| Exceptional frontier | — | Fable, if available | Long-running or unusually difficult work only |

Escalate:
- Luna/Haiku returns NEEDS_CONTEXT, BLOCKED, or needs broad repo exploration → Terra/Sonnet.
- Terra/Sonnet encounters architectural ambiguity, fails a substantive review/fix attempt, or cannot reconcile alternatives → Sol/Opus.
- Sol/Opus cannot proceed because requirements conflict or are missing → human decision.

Review:
- Mechanical Luna/Haiku diffs: cheap review only when the change is purely transcription and tests are strong; otherwise Terra/Sonnet review.
- Terra/Sonnet implementations: Terra/Sonnet review for ordinary risk; Sol/Opus for security, concurrency, migrations, public API changes, or final whole-branch review.
- Final whole-branch review: Sol/Opus.