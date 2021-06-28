package com.leaprnd.migrannotate;

@SchemaIdentifier(6067387809931810870L)
@Schema("""
CREATE TABLE "knight" (
	"id" BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
	"name" VARCHAR NOT NULL,
	"favoriteApple" BIGINT NOT NULL,
	"favoriteSauce" BIGINT NOT NULL,
	CONSTRAINT "pkKnight" PRIMARY KEY ("id"),
	CONSTRAINT "fkKnightFavoriteApple" FOREIGN KEY ("favoriteApple") REFERENCES "apple" ("id") ON UPDATE CASCADE ON DELETE CASCADE,
	CONSTRAINT "fkKnightFavoriteSauce" FOREIGN KEY ("favoriteSauce") REFERENCES "sauce" ("id") ON UPDATE CASCADE ON DELETE CASCADE
);
""")
@SchemaUpgrade(from = 1305369197L, to = 1759279540L, sql = """
ALTER TABLE "knight" ADD COLUMN "name" VARCHAR NOT NULL;
""")
@SchemaUpgrade(from = 1759279540L, to = 2612757535L, sql = """
ALTER TABLE "knight"
	ADD COLUMN "favoriteApple" BIGINT NOT NULL,
	ADD COLUMN "favoriteSauce" BIGINT NOT NULL,
	ADD CONSTRAINT "fkKnightFavoriteApple" FOREIGN KEY ("favoriteApple") REFERENCES "apple" ("id") ON UPDATE CASCADE ON DELETE CASCADE,
	ADD CONSTRAINT "fkKnightFavoriteSauce" FOREIGN KEY ("favoriteSauce") REFERENCES "sauce" ("id") ON UPDATE CASCADE ON DELETE CASCADE;
""")
@SchemaDependency(Apple.class)
@SchemaDependency(Sauce.class)
public class Knight {}