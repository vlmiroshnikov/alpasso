package alpasso.cmdline


import munit.FunSuite

class ArgParserSuite extends FunSuite:

  test("ArgParser should parse repo init command"):
    val init = ArgParser.command.parse(
      Seq(
        "repo",
        "init",
        "-p",
        ".",
        "--gpg-fingerprint",
        "5573E42BAA9D46C0F8D8C466CA6BEF44194FF928"
      ),
      sys.env
    )
    assert(init.isRight)

  test("ArgParser should parse new secret command"):
    val add = ArgParser.command.parse(Seq("new", "test-secret"))
    assert(add.isRight)

  test("ArgParser should parse ls command with grep"):
    val ls = ArgParser.command.parse(Seq("ls", "--grep", "proton"))
    assert(ls.isRight)

  test("ArgParser should parse repo list command"):
    val list = ArgParser.command.parse(Seq("repo", "list"))
    assert(list.isRight)

  test("ArgParser should parse repo switch command"):
    val switch = ArgParser.command.parse(Seq("repo", "switch", "1"))
    assert(switch.isRight)

  test("ArgParser should parse remote setup command"):
    val remote = ArgParser.command.parse(Seq("repo", "remote", "setup", "https://github.com/test/repo.git"))
    assert(remote.isRight)

  test("ArgParser should parse remote sync command"):
    val sync = ArgParser.command.parse(Seq("repo", "remote", "sync"))
    assert(sync.isRight)

  test("ArgParser should parse patch command"):
    val patch = ArgParser.command.parse(Seq("patch", "test-secret", "new-value"))
    assert(patch.isRight)

  test("ArgParser should parse remove command"):
    val remove = ArgParser.command.parse(Seq("rm", "test-secret"))
    assert(remove.isRight)

  test("ArgParser should parse ls command with output format"):
    val lsTree = ArgParser.command.parse(Seq("ls", "--output", "Tree"))
    assert(lsTree.isRight)

  test("ArgParser should parse ls command with unmasked flag"):
    val lsUnmasked = ArgParser.command.parse(Seq("ls", "--unmasked"))
    assert(lsUnmasked.isRight)

  test("ArgParser should parse repo init with short path flag"):
    val initShort = ArgParser.command.parse(
      Seq("repo", "init", "-p", "/tmp/test", "--gpg-fingerprint", "5573E42BAA9D46C0F8D8C466CA6BEF44194FF928"),
      sys.env
    )
    assert(initShort.isRight)

  test("ArgParser should parse repo init with long path flag"):
    val initLong = ArgParser.command.parse(
      Seq("repo", "init", "--path", "/tmp/test", "--gpg-fingerprint", "5573E42BAA9D46C0F8D8C466CA6BEF44194FF928"),
      sys.env
    )
    assert(initLong.isRight)

  test("ArgParser should parse repo log command"):
    val log = ArgParser.command.parse(Seq("repo", "log"))
    assert(log.isRight)

  test("ArgParser should parse remote setup with custom name"):
    val remoteCustom = ArgParser.command.parse(Seq("repo", "remote", "setup", "https://github.com/test/repo.git", "upstream"))
    assert(remoteCustom.isRight)

  test("ArgParser should parse remote setup with default name"):
    val remoteDefault = ArgParser.command.parse(Seq("repo", "remote", "setup", "https://github.com/test/repo.git"))
    assert(remoteDefault.isRight)

  test("ArgParser should parse new command with secret and metadata"):
    val newFull = ArgParser.command.parse(Seq("new", "test-secret", "my-secret", "--meta", "env=prod,team=dev"))
    assert(newFull.isRight)

  test("ArgParser should parse new command with only name"):
    val newNameOnly = ArgParser.command.parse(Seq("new", "test-secret"))
    assert(newNameOnly.isRight)

  test("ArgParser should parse new command with name and secret"):
    val newNameSecret = ArgParser.command.parse(Seq("new", "test-secret", "my-secret"))
    assert(newNameSecret.isRight)

  test("ArgParser should parse new command with metadata only"):
    val newMeta = ArgParser.command.parse(Seq("new", "test-secret", "--meta", "env=prod"))
    assert(newMeta.isRight)

  test("ArgParser should parse patch command with new payload"):
    val patchPayload = ArgParser.command.parse(Seq("patch", "test-secret", "new-payload"))
    assert(patchPayload.isRight)

  test("ArgParser should parse patch command with metadata"):
    val patchMeta = ArgParser.command.parse(Seq("patch", "test-secret", "--meta", "env=dev"))
    assert(patchMeta.isRight)

  test("ArgParser should parse patch command with both payload and metadata"):
    val patchFull = ArgParser.command.parse(Seq("patch", "test-secret", "new-payload", "--meta", "env=prod"))
    assert(patchFull.isRight)

  test("ArgParser should parse patch command with only name"):
    val patchNameOnly = ArgParser.command.parse(Seq("patch", "test-secret"))
    assert(patchNameOnly.isRight)

  test("ArgParser should parse ls command with Table output format"):
    val lsTable = ArgParser.command.parse(Seq("ls", "--output", "Table"))
    assert(lsTable.isRight)

  test("ArgParser should parse ls command with short output flag"):
    val lsShort = ArgParser.command.parse(Seq("ls", "-o", "Table"))
    assert(lsShort.isRight)

  test("ArgParser should parse ls command with grep and output"):
    val lsFull = ArgParser.command.parse(Seq("ls", "--grep", "test", "--output", "Table", "--unmasked"))
    assert(lsFull.isRight)

  test("ArgParser should parse ls command with grep and unmasked"):
    val lsGrepUnmasked = ArgParser.command.parse(Seq("ls", "--grep", "api", "--unmasked"))
    assert(lsGrepUnmasked.isRight)

  test("ArgParser should parse ls command without any options"):
    val lsEmpty = ArgParser.command.parse(Seq("ls"))
    assert(lsEmpty.isRight)

  test("ArgParser should parse remove command with complex path"):
    val removePath = ArgParser.command.parse(Seq("rm", "path/to/secret"))
    assert(removePath.isRight)

  test("ArgParser should validate invalid secret name for new command"):
    val newEmpty = ArgParser.command.parse(Seq("new", ""))
    assert(newEmpty.isLeft)

  test("ArgParser should validate invalid secret name for patch command"):
    val patchEmpty = ArgParser.command.parse(Seq("patch", ""))
    assert(patchEmpty.isLeft)

  test("ArgParser should validate invalid secret name for remove command"):
    val removeEmpty = ArgParser.command.parse(Seq("rm", ""))
    assert(removeEmpty.isLeft)

  test("ArgParser should parse repo switch with zero index"):
    val switchZero = ArgParser.command.parse(Seq("repo", "switch", "0"))
    assert(switchZero.isRight)

  test("ArgParser should parse repo switch with large index"):
    val switchLarge = ArgParser.command.parse(Seq("repo", "switch", "999"))
    assert(switchLarge.isRight)

  test("ArgParser should fail on invalid switch index"):
    val switchInvalid = ArgParser.command.parse(Seq("repo", "switch", "abc"))
    assert(switchInvalid.isLeft)

  test("ArgParser should default to Tree format for invalid output format"):
    val invalidFormat = ArgParser.command.parse(Seq("ls", "--output", "Invalid"))
    assert(invalidFormat.isRight)

end ArgParserSuite
