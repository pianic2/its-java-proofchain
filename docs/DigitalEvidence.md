# Digital evidence storage consistency

Evidence registration persists database metadata and atomically finalizes the content file within one application use case. A controlled database rollback compensates by deleting content that was already finalized.

There is an unavoidable crash window between the atomic final move and the database commit. If the process stops in that window, an orphaned content file can remain. Sprint 3 intentionally adds neither startup cleanup nor an outbox/reconciliation mechanism; operators must treat this as a documented residual recovery condition.
