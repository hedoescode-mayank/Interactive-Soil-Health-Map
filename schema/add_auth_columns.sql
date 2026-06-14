-- Migration: Add forgot-password support to farmers table
-- Run this once against your PostgreSQL database

ALTER TABLE farmers
    ADD COLUMN IF NOT EXISTS security_question   TEXT,
    ADD COLUMN IF NOT EXISTS security_answer_hash TEXT,
    ADD COLUMN IF NOT EXISTS reset_token         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reset_token_expires TIMESTAMP;

-- Index for fast token lookup
CREATE INDEX IF NOT EXISTS idx_farmers_reset_token ON farmers(reset_token)
    WHERE reset_token IS NOT NULL;
