-- Assistant transcript entries are internal accepted Run output, never direct user authority.
ALTER TABLE messages ALTER COLUMN client_message_id DROP NOT NULL;
ALTER TABLE messages ADD CONSTRAINT messages_role_provenance CHECK (
 (role='USER' AND trust='USER_DIRECT' AND client_message_id IS NOT NULL)
 OR (role='ASSISTANT' AND trust='MODEL_PROPOSAL' AND client_message_id IS NULL)
);
CREATE UNIQUE INDEX messages_one_assistant_per_run ON messages(owner_id,run_id) WHERE role='ASSISTANT';
CREATE INDEX messages_history ON messages(owner_id,conversation_id,created_at DESC,id DESC);
CREATE INDEX conversations_history ON conversations(owner_id,created_at DESC,id DESC);
CREATE INDEX runs_history ON runs(owner_id,conversation_id,created_at DESC,id DESC);
CREATE INDEX stream_events_conversation ON stream_events(owner_id,conversation_id,sequence);
