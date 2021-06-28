package com.leaprnd.migrannotate;

@SchemaIdentifier(5399356631421331000L)
@Schema("""
CREATE TABLE "jedi" (
	"knight" BIGINT NOT NULL,
	"midiChlorianDensity" FLOAT NOT NULL,
	CONSTRAINT "pkJedi" PRIMARY KEY ("knight"),
	CONSTRAINT "fkJediKnight" FOREIGN KEY ("knight") REFERENCES "knight" ("id") ON UPDATE CASCADE ON DELETE CASCADE
);
""")
@SchemaUpgrade(from = 242734274L, to = 2142535672L, sql = """
-- This migration is intentionally broken
ALTER TABLE "jedi" ADD COLUMN "midiChlorianDensity" FLOAT NOT NULL DEFAULT 'test';
""")
@SchemaDependency(Knight.class)
public class Jedi {}