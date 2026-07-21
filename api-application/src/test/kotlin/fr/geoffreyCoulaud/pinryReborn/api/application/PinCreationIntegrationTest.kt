package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.emptyIterable
import org.junit.jupiter.api.Test

@QuarkusTest
class PinCreationIntegrationTest : IntegrationTest() {

    // ==================== Simple Scenarios ====================

    @Test
    fun `creating a pin as authenticated user returns the created pin`() {
        val auth = createAuthenticatedUser()

        given()
            .contentType(ContentType.JSON)
            .authenticatedAs(auth)
            .body(
                """{
                    "sourceContextUrl": "https://example.com/page",
                    "sourceMediaUrl": "https://example.com/image.jpg",
                    "description": "A test pin"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(201)
            .header("Location", notNullValue())
            .body("id", notNullValue())
            .body("authorId", equalTo(auth.user.id.toString()))
            .body("sourceContextUrl", equalTo("https://example.com/page"))
            .body("sourceMediaUrl", equalTo("https://example.com/image.jpg"))
            .body("description", equalTo("A test pin"))
            .body("tags", emptyIterable<Any>())
    }

    @Test
    fun `creating a pin returns 201 Created status`() {
        val auth = createAuthenticatedUser()

        given()
            .contentType(ContentType.JSON)
            .authenticatedAs(auth)
            .body(
                """{
                    "sourceContextUrl": "https://status.com",
                    "sourceMediaUrl": "https://status.com/img.png",
                    "description": "Status test"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(201)
    }

    // ==================== Complex Scenarios ====================

    @Test
    fun `creating a pin without authentication fails with 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(
                """{
                    "sourceContextUrl": "https://example.com/page",
                    "sourceMediaUrl": "https://example.com/image.jpg",
                    "description": "A test pin"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(401)
    }

    @Test
    fun `creating a pin with an invalid bearer token fails with 401`() {
        // Given: a real user exists, but the request carries a tampered (invalid) token, which is
        // the Bearer equivalent of a per-request "wrong password" under the old Basic scheme
        createAuthenticatedUser()

        given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer not-a-real-token")
            .body(
                """{
                    "sourceContextUrl": "https://example.com/page",
                    "sourceMediaUrl": "https://example.com/image.jpg",
                    "description": "A test pin"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(401)
    }

    @Test
    fun `requesting pins with an invalid bearer token fails with a RFC 7807 problem`() {
        given()
            .header("Authorization", "Bearer not-a-real-token")
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTHENTICATION_FAILED"))
            .header("WWW-Authenticate", containsString("Bearer"))
    }

    @Test
    fun `requesting pins without credentials fails with a RFC 7807 problem`() {
        given()
            .`when`()
            .get("/api/v1/pins")
            .then()
            .statusCode(401)
            .contentType("application/problem+json")
            .body("code", equalTo("AUTHENTICATION_REQUIRED"))
            .header("WWW-Authenticate", containsString("Bearer"))
    }

    @Test
    fun `creating multiple pins as same user succeeds`() {
        val auth = createAuthenticatedUser()

        // Create first pin
        given()
            .contentType(ContentType.JSON)
            .authenticatedAs(auth)
            .body(
                """{
                    "sourceContextUrl": "https://first.com",
                    "sourceMediaUrl": "https://first.com/img.jpg",
                    "description": "First pin"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(201)
            .body("description", equalTo("First pin"))

        // Create second pin
        given()
            .contentType(ContentType.JSON)
            .authenticatedAs(auth)
            .body(
                """{
                    "sourceContextUrl": "https://second.com",
                    "sourceMediaUrl": "https://second.com/img.jpg",
                    "description": "Second pin"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(201)
            .body("description", equalTo("Second pin"))
    }

    @Test
    fun `creating a pin with unicode description succeeds`() {
        val auth = createAuthenticatedUser()

        given()
            .contentType(ContentType.JSON)
            .authenticatedAs(auth)
            .body(
                """{
                    "sourceContextUrl": "https://unicode.com",
                    "sourceMediaUrl": "https://unicode.com/img.jpg",
                    "description": "描述文字 🎉 émojis"
                }"""
            )
            .`when`()
            .post("/api/v1/pins")
            .then()
            .statusCode(201)
            .body("description", equalTo("描述文字 🎉 émojis"))
    }
}
