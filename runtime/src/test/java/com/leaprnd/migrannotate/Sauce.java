package com.leaprnd.migrannotate;

@SchemaIdentifier(936908912345077096L)
@RepeatableSchema("""
CREATE TABLE IF NOT EXISTS "sauce" ();
ALTER TABLE "sauce"
	ADD COLUMN IF NOT EXISTS "id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	ADD COLUMN IF NOT EXISTS "name" VARCHAR NOT NULL;
""")
public class Sauce {}