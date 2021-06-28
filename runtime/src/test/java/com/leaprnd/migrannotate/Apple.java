package com.leaprnd.migrannotate;

@SchemaIdentifier(-8388116365589044375L)
@Schema("""
CREATE TABLE "apple" (
	"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
	"name" VARCHAR NOT NULL,
	CONSTRAINT "pkApple" PRIMARY KEY ("id")
);
""")
public class Apple {

}
