package fr.geoffreyCoulaud.pinryReborn.api.application

import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.emptyIterable
import org.junit.jupiter.api.Test

@QuarkusTest
class PinCreationIntegrationTest : IntegrationTest() {

    @Inject
    lateinit var userCreator: UserCreator

    // ==================== Simple Scenarios ====================

    @Test
    fun `creating a pin as authenticated user returns the created pin`() {
        val username = "pinuser"
        val password = "password123"
        val user = userCreator.createUserWithPassword(username, password)

        given()
            .contentType(ContentType.JSON)
            .auth().preemptive().basic(username, password)
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
            .body("authorId", equalTo(user.id.toString()))
            .body("sourceContextUrl", equalTo("https://example.com/page"))
            .body("sourceMediaUrl", equalTo("https://example.com/image.jpg"))
            .body("description", equalTo("A test pin"))
            .body("tags", emptyIterable<Any>())
    }

    @Test
    fun `creating a pin returns 201 Created status`() {
        val username = "statususer"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        given()
            .contentType(ContentType.JSON)
            .auth().preemptive().basic(username, password)
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
    fun `creating a pin with wrong password fails with 401`() {
        val username = "wrongpassuser"
        val password = "correctpassword"
        userCreator.createUserWithPassword(username, password)

        given()
            .contentType(ContentType.JSON)
            .auth().preemptive().basic(username, "wrongpassword")
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
    fun `creating a pin with non-existent user fails with 401`() {
        given()
            .contentType(ContentType.JSON)
            .auth().preemptive().basic("nonexistent", "password")
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
    fun `creating multiple pins as same user succeeds`() {
        val username = "multipleuser"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        // Create first pin
        given()
            .contentType(ContentType.JSON)
            .auth().preemptive().basic(username, password)
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
            .auth().preemptive().basic(username, password)
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
        val username = "unicodeuser"
        val password = "password123"
        userCreator.createUserWithPassword(username, password)

        given()
            .contentType(ContentType.JSON)
            .auth().preemptive().basic(username, password)
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
