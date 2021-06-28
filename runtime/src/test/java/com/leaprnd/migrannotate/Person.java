package com.leaprnd.migrannotate;

import static com.leaprnd.migrannotate.Person.PERSON_GROUP;

@SchemaIdentifier(936908912751334464L)
@SchemaGroup(PERSON_GROUP)
@Schema("""
CREATE TABLE "person" (
	"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
	"favoriteColor" "color" NOT NULL,
	"handedness" "handedness" NOT NULL,
	CONSTRAINT "pkPerson" PRIMARY KEY ("id")
);
""")
@EnumSchema(name = "color", value = Color.class)
@EnumSchema(name = "handedness", value = Handedness.class)
@SchemaUpgrade(from = 2930944258L, to = 2449140426L, sql = """
ALTER TABLE "person" ADD COLUMN "handedness" "handedness" NOT NULL;
""")
public class Person {
	public static final String PERSON_GROUP = "person";
}