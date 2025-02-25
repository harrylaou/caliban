package caliban.schema

import caliban.*
import caliban.schema.Annotations.{ GQLField, GQLInterface, GQLName }
import zio.test.{ assertTrue, ZIOSpecDefault }
import zio.{ RIO, Task, ZIO }

import java.time.Instant

object Scala3DerivesSpec extends ZIOSpecDefault {

  override def spec = suite("Scala3DerivesSpec") {

    val expected =
      """schema {
        |  query: Bar
        |}

        |type Bar {
        |  foo: Foo!
        |}

        |type Foo {
        |  value: String!
        |}""".stripMargin

    List(
      test("SemiAuto derivation - default") {
        final case class Foo(value: String) derives Schema.SemiAuto
        final case class Bar(foo: Foo) derives Schema.SemiAuto

        val gql = graphQL(RootResolver(Bar(Foo("foo"))))

        assertTrue(gql.render == expected)
      },
      test("Auto derivation - default") {
        final case class Foo(value: String)
        final case class Bar(foo: Foo) derives Schema.Auto

        val gql = graphQL(RootResolver(Bar(Foo("foo"))))

        assertTrue(gql.render == expected)
      },
      test("Auto derivation - custom R") {
        class Env
        object CustomSchema extends SchemaDerivation[Env]
        final case class Foo(value: String)
        final case class Bar(foo: Foo) derives CustomSchema.Auto

        val gql = graphQL(RootResolver(Bar(Foo("foo"))))

        assertTrue(gql.render == expected)
      },
      test("SemiAuto derivation - custom R") {
        class Env
        object CustomSchema extends SchemaDerivation[Env]
        final case class Foo(value: String) derives CustomSchema.SemiAuto
        final case class Bar(foo: Foo) derives CustomSchema.SemiAuto

        val gql = graphQL(RootResolver(Bar(Foo("foo"))))

        assertTrue(gql.render == expected)
      },
      suite("ArgBuilder derivation") {
        val expected =
          """schema {
            |  query: Query
            |}

            |type Bar {
            |  foo: Foo!
            |}

            |type Foo {
            |  s: String!
            |}

            |type Query {
            |  f(s: String!): Bar!
            |}""".stripMargin

        List(
          test("SemiAuto") {
            final case class Foo(s: String) derives Schema.SemiAuto, ArgBuilder
            final case class Bar(foo: Foo) derives Schema.SemiAuto
            final case class Query(f: Foo => Bar) derives Schema.SemiAuto

            val gql = graphQL(RootResolver(Query(Bar(_))))

            assertTrue(gql.render == expected)
          },
          test("Auto") {
            final case class Foo(s: String) derives Schema.Auto, ArgBuilder.GenAuto
            final case class Bar(foo: Foo) derives Schema.SemiAuto
            final case class Query(f: Foo => Bar) derives Schema.SemiAuto

            val gql = graphQL(RootResolver(Query(Bar(_))))

            assertTrue(gql.render == expected)
          }
        )
      },
      suite("derivation of case classes containing Instants")(
        test("product schema") {
          final case class Foo(i: Instant) derives Schema.SemiAuto, ArgBuilder
          final case class Bar(foo: Foo) derives Schema.SemiAuto
          final case class Query(f: Foo => Bar) derives Schema.SemiAuto

          val gql = graphQL(RootResolver(Query(Bar(_))))
          assertTrue(
            gql.render ==
              """schema {
                |  query: Query
                |}
                |"An instantaneous point on the time-line represented by a standard date time string"
                |scalar Instant

                |type Bar {
                |  foo: Foo!
                |}

                |type Foo {
                |  i: Instant!
                |}

                |type Query {
                |  f(i: Instant!): Bar!
                |}""".stripMargin
          )
        },
        test("sum schema") {
          @GQLInterface
          sealed trait Foo derives Schema.SemiAuto {
            val i: Instant
          }
          object Foo {
            final case class FooA(i: Instant, s1: String) extends Foo derives Schema.SemiAuto, ArgBuilder
            final case class FooB(i: Instant, i1: Int)    extends Foo derives Schema.SemiAuto, ArgBuilder
          }

          final case class Bar(foo: Foo) derives Schema.SemiAuto
          final case class Query(f: Foo.FooA => Bar) derives Schema.SemiAuto

          val gql = graphQL(RootResolver(Query(Bar(_))))

          assertTrue(
            gql.render ==
              """schema {
                |  query: Query
                |}
                |"An instantaneous point on the time-line represented by a standard date time string"
                |scalar Instant

                |interface Foo {
                |  i: Instant!
                |}

                |type Bar {
                |  foo: Foo!
                |}

                |type FooA implements Foo {
                |  i: Instant!
                |  s1: String!
                |}

                |type FooB implements Foo {
                |  i: Instant!
                |  i1: Int!
                |}

                |type Query {
                |  f(i: Instant!, s1: String!): Bar!
                |}""".stripMargin
          )
        }
      ),
      suite("methods as fields") {
        val expectedSchema =
          """schema {
            |  query: Bar
            |}

            |type Bar {
            |  foo: Foo!
            |}

            |type Foo {
            |  value: String!
            |  value2: String
            |}""".stripMargin
        List(
          test("SemiAuto derivation of methods as fields") {
            final case class Foo(value: String) derives Schema.SemiAuto {
              def value1: String                   = value + 1
              @GQLField def value2: Option[String] = Some(value + 2)
            }
            final case class Bar(foo: Foo) derives Schema.SemiAuto
            val rendered = graphQL(RootResolver(Bar(Foo("foo")))).render

            assertTrue(rendered == expectedSchema)
          },
          test("custom schema derivation") {
            trait MyService
            object MySchema extends SchemaDerivation[MyService]
            final case class Foo(value: String) derives MySchema.SemiAuto {
              @GQLField def value2: RIO[MyService, Option[String]] = ZIO.some(value + 2)
            }
            final case class Bar(foo: Foo) derives MySchema.SemiAuto
            val rendered = graphQL(RootResolver(Bar(Foo("foo")))).render

            assertTrue(rendered == expectedSchema)
          },
          test("method annotations") {
            final case class Foo(value: String) derives Schema.SemiAuto {
              @GQLField
              @GQLName("value2")
              def foo: Option[String] = Some(value + 2)
            }
            final case class Bar(foo: Foo) derives Schema.SemiAuto
            val rendered = graphQL(RootResolver(Bar(Foo("foo")))).render

            assertTrue(rendered == expectedSchema)
          },
          test("Auto derivation of methods as fields") {
            final case class Foo(value: String) {
              @GQLField def value2: Option[String] = Some(value + 2)
            }
            final case class Bar(foo: Foo) derives Schema.Auto
            val rendered = graphQL(RootResolver(Bar(Foo("foo")))).render
            assertTrue(rendered == expectedSchema)
          },
          test("execution of methods as fields") {
            final case class Foo(value: String) derives Schema.SemiAuto {
              @GQLField def value2: Task[String] = ZIO.succeed(value + 2)
            }
            final case class Bar(foo: Foo) derives Schema.SemiAuto
            val gql = graphQL(RootResolver(Bar(Foo("foo"))))

            gql.interpreter.flatMap { i =>
              i.execute("{foo {value value2}}").map { v =>
                val s = v.data.toString
                assertTrue(s == """{"foo":{"value":"foo","value2":"foo2"}}""")
              }
            }
          }
        )
      }
    )
  }
}
