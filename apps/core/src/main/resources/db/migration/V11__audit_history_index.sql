-- Owner equality and descending timestamp/UUID keyset traversal for read-only audit history.
CREATE INDEX audit_events_owner_history ON audit_events USING btree (owner_id,happened_at DESC,id DESC);
