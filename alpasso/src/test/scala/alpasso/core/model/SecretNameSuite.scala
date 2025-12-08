package alpasso.core.model

import alpasso.domain.SecretName
import java.nio.file.Path

import cats.syntax.show.*

import munit.FunSuite

class SecretNameSuite extends FunSuite:

  test("SecretName.of should validate empty names"):
    assert(SecretName.of("").isInvalid)

  test("SecretName.of should create valid name from non-empty string"):
    assert(SecretName.of("test-secret").isValid)

  test("shortName should return last path segment as string"):
    val secret = SecretName.of("path/to/secret").toOption.get
    assertEquals(secret.shortName, "secret")

  test("shortName should return filename from absolute path"):
    val secret = SecretName.of("/absolute/path/to/secret.txt").toOption.get
    assertEquals(secret.shortName, "secret.txt")

  test("shortName should handle simple filename without path"):
    val secret = SecretName.of("simpleName").toOption.get
    assertEquals(secret.shortName, "simpleName")

  test("shortName should return empty string for root path"):
    val secret = SecretName.of("/").toOption.get
    assertEquals(secret.shortName, "")

  test("shortName should handle path with trailing slash"):
    val secret = SecretName.of("path/to/dir/").toOption.get
    assertEquals(secret.shortName, "dir")

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
