package alpasso.core.model

import java.nio.file.Path

import cats.syntax.show.*

import alpasso.core.model.SecretName

import munit.FunSuite

class SecretNameSuite extends FunSuite:

  test("SecretName.of should validate empty names"):
    assert(SecretName.of("").isInvalid)

  test("SecretName.of should create valid name from non-empty string"):
    assert(SecretName.of("test-secret").isValid)

  test("shortName should return last path segment as string"):
    val secret = SecretName.of("path/to/secret").toOption.get
    assertEquals(secret.shortName, "secret")

  test("trims trailing slashes"):
    val secret = SecretName.of("path/to/secret/").toOption.get
    assertEquals(secret.asPath, Path.of("path/to/secret"))

  test("normalizes path"):
    val secret = SecretName.of("path/../secret/").toOption.get
    assertEquals(secret.asPath, Path.of("secret"))

  test("Show instance should display short name correctly"):
    val s = SecretName.of("path/to/secret").toOption.get
    assertEquals(s.show, "secret")

end SecretNameSuite
