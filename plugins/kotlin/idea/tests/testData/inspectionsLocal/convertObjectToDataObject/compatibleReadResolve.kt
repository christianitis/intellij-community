// COMPILER_ARGUMENTS: -XXLanguage:+DataObjects
import java.io.Serializable

object<caret> Foo : Serializable {
    private fun readResolve(): Any = Foo
}
