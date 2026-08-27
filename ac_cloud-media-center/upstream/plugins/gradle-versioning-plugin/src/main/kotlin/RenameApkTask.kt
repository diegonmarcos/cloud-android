import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class RenameApkTask : DefaultTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val newFileName: Property<String>

    @TaskAction
    fun taskAction() {
        val outDir = outputDir.get().asFile
        outDir.listFiles()?.forEach { it.delete() }

        inputDir.get().asFile.listFiles()?.filter { it.extension == "apk" }?.forEach { apk ->
            apk.copyTo(File(outDir, "${newFileName.get()}.apk"), overwrite = true)
        }
    }
}
