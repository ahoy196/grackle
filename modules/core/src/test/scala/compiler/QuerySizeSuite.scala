// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package compiler

import cats.data.NonEmptyChain
import munit.CatsEffectSuite

import edu.gemini.grackle.{Problem, Result}
import starwars.StarWarsMapping

class QuerySizeSuite extends CatsEffectSuite {

  test("depth 2 query") {
    val query = """
      query {
        character(id: "1000") {
          name
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((2,1)))
  }

  test("also depth 2 query") {
    val query = """
      query {
        character(id: "1000") {
          name
          id
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((2,2)))
  }

  test("depth 3 query") {
    val query = """
      query {
        character(id: "1000") {
          friends {
            name
          }
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((3,1)))
  }

  test("depth 4 query") {
    val query = """
      query {
        character(id: "1000") {
          name
          friends {
            name
            friends {
              name
            }
          }
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((4,3)))
  }
  test("aliased depth 2 query") {

    val query =
      """
      query {
        luke: character(id: "1000") {
          handle: name
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((2,1)))
  }

  test("grouplist depth 2 query") {

    val query =
      """
      query {
        character(id: "1000") {
          appearsIn
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((2,1)))
  }

  test("fragments depth 3 query") {
    val query = """
      query {
        character(id: "1000") {
          name
          friends {
            name
            ... on Human {
              homePlanet
              appearsIn
            }
            ... on Droid {
              primaryFunction
            }
          }
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assertEquals(res, ((3,5)))
  }

  test("width 2 query") {
    val query = """
      query {
        character(id: "1000") {
          name
          id
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assert(res._2 == 2)
  }

  test("width 5 query") {
    val query = """
      query {
        character(id: "1000") {
          name
          friends {
            name
            id
            friends {
              name
              id
            }
          }
        }
      }
    """

    val compiledQuery = StarWarsMapping.compiler.compile(query).toOption.get.query
    val res = StarWarsMapping.querySizeValidator.querySize(compiledQuery, Map.empty)

    assert(res._2 == 5)
  }

  test("query too deep") {
    val query = """
      query {
        character(id: "1000") {
          name
          friends {
            name
            friends {
              name
              friends {
                name
                friends {
                  name
                  friends {
                    name
                    friends {
                      name
                    }
                  }
                }
              }
            }
          }
        }
      }
    """

    val expected = Problem("Query is too deep: depth is 8 levels, maximum is 5")

    val res = StarWarsMapping.compiler.compile(query)
    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }

  test("query too wide") {
    val query = """
      query {
        character(id: "1000") {
          name
          id
          friends {
            name
            id
            friends {
              name
              id
            }
          }
        }
      }
    """


    val expected = Problem("Query is too wide: width is 6 leaves, maximum is 5")

    val res = StarWarsMapping.compiler.compile(query)
    assertEquals(res, Result.Failure(NonEmptyChain(expected)))
  }
}
