package ai.openclaw.script

import org.junit.Assert.*
import org.junit.Test

class ScriptValidatorTest {

    @Test fun `valid simple script passes`() {
        assertTrue(ScriptValidator.validate("return 1 + 1;").isValid)
    }

    @Test fun `valid multi-line script passes`() {
        val script = "var x = 10;\nvar y = 20;\nreturn x + y;"
        assertTrue(ScriptValidator.validate(script).isValid)
    }

    @Test fun `valid script with function calls passes`() {
        assertTrue(ScriptValidator.validate("var r = fs.readFile(\"data.txt\"); return r;").isValid)
    }

    @Test fun `empty script fails`() {
        val r = ScriptValidator.validate("")
        assertFalse(r.isValid); assertTrue(r.error!!.contains("不能为空"))
    }

    @Test fun `blank script fails`() {
        assertFalse(ScriptValidator.validate("   \n  \t  ").isValid)
    }

    @Test fun `oversized script fails`() {
        val r = ScriptValidator.validate("var x = 1; ".repeat(6000))
        assertFalse(r.isValid); assertTrue(r.error!!.contains("过长"))
    }

    @Test fun `import blocked`() {
        val r = ScriptValidator.validate("import fs from 'fs';")
        assertFalse(r.isValid); assertTrue(r.error!!.contains("禁止"))
    }

    @Test fun `require blocked`() { assertFalse(ScriptValidator.validate("var fs = require('fs');").isValid) }
    @Test fun `eval blocked`() { assertFalse(ScriptValidator.validate("eval('alert(1)');").isValid) }
    @Test fun `new Function blocked`() { assertFalse(ScriptValidator.validate("var f = new Function('return 1');").isValid) }
    @Test fun `setTimeout blocked`() { assertFalse(ScriptValidator.validate("setTimeout(function(){}, 1000);").isValid) }
    @Test fun `setInterval blocked`() { assertFalse(ScriptValidator.validate("setInterval(function(){}, 1000);").isValid) }
    @Test fun `java package blocked`() { assertFalse(ScriptValidator.validate("java.lang.System.exit(0);").isValid) }
    @Test fun `android package blocked`() { assertFalse(ScriptValidator.validate("android.os.Process.killProcess(0);").isValid) }
    @Test fun `Packages blocked`() { assertFalse(ScriptValidator.validate("Packages.java.lang.System;").isValid) }
    @Test fun `process blocked`() { assertFalse(ScriptValidator.validate("process.exit(1);").isValid) }
    @Test fun `global blocked`() { assertFalse(ScriptValidator.validate("global.someVar = 1;").isValid) }
    @Test fun `globalThis blocked`() { assertFalse(ScriptValidator.validate("globalThis.x = 1;").isValid) }
    @Test fun `window blocked`() { assertFalse(ScriptValidator.validate("window.location = 'http://evil.com';").isValid) }
    @Test fun `document blocked`() { assertFalse(ScriptValidator.validate("document.cookie;").isValid) }
    @Test fun `constructor prototype blocked`() { assertFalse(ScriptValidator.validate("constructor.prototype.x = 1;").isValid) }
    @Test fun `__proto__ blocked`() { assertFalse(ScriptValidator.validate("{}.__proto__.polluted = 1;").isValid) }
}
