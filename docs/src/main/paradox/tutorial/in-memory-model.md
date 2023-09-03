# In-Memory Model

The GraphQL reference implementation defines an API over a simple data model representing characters and films from
the Star Wars series. Because of its appearance in the reference implementation it is used as the basis for many
GraphQL tutorials, and many GraphQL server implementations provide it as an example. Grackle is no exception.

In this tutorial we are going to implement the Star Wars demo using Grackle backed by an in-memory model, i.e. a
simple Scala data structure which captures the information required to service the GraphQL API.

## Running the demo

The demo is packaged as submodule `demo` in the Grackle project. It is a http4s-based application which can be run
from the SBT REPL using `sbt-revolver`,

```
sbt:gsp-graphql> reStart
[info] Application demo not yet started
[info] Starting application demo in the background ...
demo Starting demo.Main.main()
demo[ERROR] Picked up JAVA_TOOL_OPTIONS:  -Xmx3489m
[success] Total time: 0 s, completed Sep 3, 2023, 5:01:11 AM
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.license.VersionPrinter printVersionOnly
demo[ERROR] INFO: Flyway Community Edition 9.22.0 by Redgate
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.license.VersionPrinter printVersion
demo[ERROR] INFO: See release notes here: https://rd.gt/416ObMi
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.license.VersionPrinter printVersion
demo[ERROR] INFO: 
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.FlywayExecutor execute
demo[ERROR] INFO: Database: jdbc:postgresql://0.0.0.0:32771/test (PostgreSQL 11.8)
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.schemahistory.JdbcTableSchemaHistory allAppliedMigrations
demo[ERROR] INFO: Schema history table "public"."flyway_schema_history" does not exist yet
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.command.DbValidate validate
demo[ERROR] INFO: Successfully validated 1 migration (execution time 00:00.059s)
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.schemahistory.JdbcTableSchemaHistory create
demo[ERROR] INFO: Creating Schema History table "public"."flyway_schema_history" ...
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.command.DbMigrate migrateGroup
demo[ERROR] INFO: Current version of schema "public": << Empty Schema >>
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.command.DbMigrate doMigrateGroup
demo[ERROR] INFO: Migrating schema "public" to version "1 - WorldSetup"
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.sqlscript.DefaultSqlScriptExecutor printWarnings
demo[ERROR] WARNING: DB: there is already a transaction in progress (SQL State: 25001 - Error Code: 0)
demo[ERROR] Sep 03, 2023 5:01:18 AM org.flywaydb.core.internal.command.DbMigrate logSummary
demo[ERROR] INFO: Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.110s)
demo [io-compute-2] INFO  o.h.e.s.EmberServerBuilderCompanionPlatform - Ember-Server service bound to address: [::]:8080 
```

This application hosts the demo services for in-memory and db-backend models, as well as a web-based GraphQL client
(GraphQL Playground) which can be used to interact with them. You can run the client for in-memory model in your browser
at [http://localhost:8080/playground.html?endpoint=starwars](http://localhost:8080/playground.html?endpoint=starwars).

## Query examples

You can use the Playground to run queries against the model. Paste the following into the query field on left,

```graphql
query {
  hero(episode: EMPIRE) {
    name
    appearsIn
  }
}
```

Click the play button in the centre and you should see the following response on the right,

```json
{
  "data": {
    "hero": {
      "name": "Luke Skywalker",
      "appearsIn": [
        "NEWHOPE",
        "EMPIRE",
        "JEDI"
      ]
    }
  }
}
```

## The Schema

The Star Wars API is described by a GraphQL schema,

```graphql
type Query {
  hero(episode: Episode!): Character
  character(id: ID!): Character
  human(id: ID!): Human
  droid(id: ID!): Droid
}

enum Episode {
  NEWHOPE
  EMPIRE
  JEDI
}

interface Character {
  id: ID!
  name: String!
  friends: [Character]
  appearsIn: [Episode]!
}

type Droid implements Character {
  id: ID!
  name: String!
  friends: [Character]
  appearsIn: [Episode]!
  primaryFunction: String
}

type Human implements Character {
  id: ID!
  name: String!
  friends: [Character]
  appearsIn: [Episode]!
  homePlanet: String
}
```

Any one of the parametrized fields in the `Query` type may be used as the top level query, with nested queries over
fields of the result type. The structure of the query follows the schema, and the structure of the result follows the
structure of the query. For example,

```graphql
query {
  character(id: 1002) {
    name
    friends {
      name
    }
  }
}
```
yields the result,

```json
{
  "data": {
    "character": {
      "name": "Han Solo",
      "friends": [
        {
          "name": "Luke Skywalker"
        },
        {
          "name": "Leia Organa"
        },
        {
          "name": "R2-D2"
        }
      ]
    }
  }
}
```

Grackle represents schemas as a Scala value of type `Schema` which can be constructed given a schema text,

@@snip [StarWarsSchema.scala](/demo/src/main/scala/demo/starwars/StarWarsMapping.scala) { #schema }

## The Scala model

The API is backed by values of an ordinary Scala data types with no Grackle dependencies,

@@snip [StarWarsData.scala](/demo/src/main/scala/demo/starwars/StarWarsMapping.scala) { #model_types }

The data structure is slightly complicated by the need to support cycles of friendship, e.g.,

```graphql
query {
  character(id: 1000) {
    name
    friends {
      name
      friends {
        name
      }
    }
  }
}
```

yields,

```json
{
  "data": {
    "character": {
      "name": "Luke Skywalker",
      "friends": [
        {
          "name": "Han Solo",
          "friends": [
            {
              "name": "Luke Skywalker"
            },
            ...
        }
    }
  }
}
```

Here the root of the result is "Luke Skywalker" and we loop back to Luke through the mutual friendship with Han Solo.

The data type is not itself recursive, instead friend are identified by their ids. When traversing through the list of
friends the `resolveFriends` method is used to locate the next `Character` value to visit.

@@snip [StarWarsData.scala](/demo/src/main/scala/demo/starwars/StarWarsMapping.scala) { #model_values }

## The query compiler and elaborator

The GraphQL queries are compiled into values of a Scala ADT which represents a query algebra. These query algebra terms
are then transformed in a variety of ways, resulting in a program which can be interpreted against the model to
produce the query result. The process of transforming these values is called _elaboration_, and each elaboration step
simplifies or expands the term to bring it into a form which can be executed directly by the query interpreter.

Grackle's query algebra consists of the following elements,

```scala
sealed trait Query {
  case class Select(name: String, args: List[Binding], child: Query = Empty) extends Query
  case class Group(queries: List[Query]) extends Query
  case class Unique(child: Query) extends Query
  case class Filter(pred: Predicate, child: Query) extends Query
  case class Component[F[_]](mapping: Mapping[F], join: (Query, Cursor) => Result[Query], child: Query) extends Query
  case class Effect[F[_]](handler: EffectHandler[F], child: Query) extends Query
  case class Introspect(schema: Schema, child: Query) extends Query
  case class Environment(env: Env, child: Query) extends Query
  case class Wrap(name: String, child: Query) extends Query
  case class Rename(name: String, child: Query) extends Query
  case class UntypedNarrow(tpnme: String, child: Query) extends Query
  case class Narrow(subtpe: TypeRef, child: Query) extends Query
  case class Skip(sense: Boolean, cond: Value, child: Query) extends Query
  case class Limit(num: Int, child: Query) extends Query
  case class Offset(num: Int, child: Query) extends Query
  case class OrderBy(selections: OrderSelections, child: Query) extends Query
  case class Count(name: String, child: Query) extends Query
  case class TransformCursor(f: Cursor => Result[Cursor], child: Query) extends Query
  case object Skipped extends Query
  case object Empty extends Query
}
```

A simple query like this,

```graphql
query {
  character(id: 1000) {
    name
  }
}
```

is first translated into a term in the query algebra of the form,

```scala
Select("character", List(IntBinding("id", 1000)),
  Select("name", Nil)
)
```

This first step is performed without reference to a GraphQL schema, hence the `id` argument is initially inferred to
be of GraphQL type `Int`.

Following this initial translation the Star Wars example has a single elaboration step whose role is to translate the
selection into something executable. Elaboration uses the GraphQL schema and so is able to translate an input value
parsed as an `Int` into a GraphQL `ID`. The semantics associated with this (i.e. what an `id` is and how it relates to
the model) is specific to this model, so we have to provide that semantic via some model-specific code,

@@snip [StarWarsData.scala](/demo/src/main/scala/demo/starwars/StarWarsMapping.scala) { #elaborator }

Extracting out the case for the `character` selector,

```scala
case Select(f@("character"), List(Binding("id", IDValue(id))), child) =>
  Select(f, Nil, Unique(Filter(Eql(CharacterType / "id", Const(id)), child))).success

```

we can see that this transforms the previous term as follows,

```scala
Select("character", Nil,
  Unique(Eql(CharacterType / "id"), Const("1000")), Select("name", Nil))
)
```

Here the argument to the `character` selector has been translated into a predicate which refines the root data of the
model to the single element which satisfies it via `Unique`. The remainder of the query (`Select("name", Nil)`) is
then within the scope of that constraint. We have eliminated something with model-specific semantics (`character(id:
1000)`) in favour of something universal which can be interpreted directly against the model.

## The query interpreter and cursor

The data required to construct the response to a query is determined by the structure of the query and gives rise to a
more or less arbitrary traversal of the model. To support this Grackle provides a functional `Cursor` abstraction
which points into the model and can navigate through GraphQL fields, arrays and values as required by a given query.

For in-memory models where the structure of the model ADT closely matches the GraphQL schema a `Cursor` can be derived
automatically by a `GenericMapping` which only needs to be supplemented with a specification of the root mappings for
the top level fields of the GraphQL schema. The root mappings enable the query interpreter to construct an appropriate
initial `Cursor` for the query being evaluated.

For the Star Wars model the root definitions are of the following form,

@@snip [StarWarsData.scala](/demo/src/main/scala/demo/starwars/StarWarsMapping.scala) { #root }

The first argument of the `GenericRoot` constructor correspond to the top-level selection of the query (see the
schema above) and the second argument is the initial model value for which a `Cursor` will be derived.  When the query
is executed, navigation will start with that `Cursor` and the corresponding GraphQL type.

## The service

What we've seen so far allows us to compile and execute GraphQL queries against our in-memory model. We now need to
expose that via HTTP. The following shows how we do that for http4s,

@@snip [StarWarsService.scala](/demo/src/main/scala/demo/GraphQLService.scala) { #service }

The GraphQL specification supports queries both via HTTP GET and POST requests, so we provide routes for both methods.
For queries via GET the query is embedded in the URI query string in the form `... ?query=<URI encoded GraphQL
query>`. For queries via POST, the query is embedded in a JSON value of the form,

```json
{
  "operationName": "Query",
  "query": "character(id: 1000) ..."
}
```

In each of these cases we extract the operation name and query from the request and pass them to the service for
compilation and execution.

Many GraphQL client tools expect servers to be able to respond to a query named `IntrospectionQuery` returning a
representation of the GraphQL schema supported by the endpoint which they use to provide client-side highlighting,
validation, auto completion etc. The demo service provides this as well as normal query execution.

## Putting it all together

Finally we need to run all of this on top of http4s. Here we have a simple `IOApp` running a `BlazeServer` with the
`StarWarsService` defined above, and a `ResourceService` to serve the GraphQL Playground web client,

```scala
object Main extends IOApp {
  def run(args: List[String]) = {
    val starWarsGraphQLRoutes = GraphQLService.routes[IO](
      "starwars",
      GraphQLService.fromGenericIdMapping(StarWarsMapping)
    )
    DemoServer.stream[IO](starWarsGraphQLRoutes).compile.drain
  }
}
```

@@snip [main.scala](/demo/src/main/scala/demo/DemoServer.scala) { #server }
