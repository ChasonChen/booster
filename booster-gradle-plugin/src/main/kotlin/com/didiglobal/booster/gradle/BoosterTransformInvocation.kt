package com.didiglobal.booster.gradle

import com.android.build.api.transform.Context
import com.android.build.api.transform.Format
import com.android.build.api.transform.SecondaryInput
import com.android.build.api.transform.Status
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactScope.ALL
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.AAR
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.JAR
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH
import com.didiglobal.booster.kotlinx.ifNotEmpty
import com.didiglobal.booster.transform.ArtifactManager
import com.didiglobal.booster.transform.TransformContext
import com.didiglobal.booster.transform.TransformListener
import com.didiglobal.booster.transform.Transformer
import com.didiglobal.booster.transform.util.transform
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.ForkJoinPool

/**
 * Represents a delegate of TransformInvocation
 *
 * @author johnsonlee
 */
internal class BoosterTransformInvocation(private val delegate: TransformInvocation) : TransformInvocation, TransformContext, TransformListener, ArtifactManager {

    /*
     * Preload transformers as List to fix NoSuchElementException caused by ServiceLoader in parallel mode
     */
    private val transformers = ServiceLoader.load(Transformer::class.java, javaClass.classLoader).toList()

    override val name: String = delegate.context.variantName

    override val projectDir: File = delegate.project.projectDir

    override val buildDir: File = delegate.project.buildDir

    override val temporaryDir: File = delegate.context.temporaryDir

    override val executor = ForkJoinPool(Runtime.getRuntime().availableProcessors(), ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true)

    override val compileClasspath = delegate.compileClasspath

    override val runtimeClasspath = delegate.runtimeClasspath

    override val artifacts = this

    override fun hasProperty(name: String): Boolean {
        return project.hasProperty(name)
    }

    override fun getProperty(name: String): String? {
        return project.properties[name]?.toString()
    }

    override fun getInputs(): MutableCollection<TransformInput> = delegate.inputs

    override fun getSecondaryInputs(): MutableCollection<SecondaryInput> = delegate.secondaryInputs

    override fun getReferencedInputs(): MutableCollection<TransformInput> = delegate.referencedInputs

    override fun isIncremental() = delegate.isIncremental

    override fun getOutputProvider(): TransformOutputProvider = delegate.outputProvider

    override fun getContext(): Context = delegate.context

    override fun onPreTransform(context: TransformContext) {
        transformers.forEach {
            it.onPreTransform(this)
        }
    }

    override fun onPostTransform(context: TransformContext) {
        transformers.forEach {
            it.onPostTransform(this)
        }
    }

    override fun get(type: String): Collection<File> {
        when (type) {
            ArtifactManager.AAR -> return variant.scope.getArtifactCollection(RUNTIME_CLASSPATH, ALL, AAR).artifactFiles.files
            ArtifactManager.ALL_CLASSES -> return variant.scope.allClasses
            ArtifactManager.APK -> return variant.scope.apk
            ArtifactManager.JAR -> return variant.scope.getArtifactCollection(RUNTIME_CLASSPATH, ALL, JAR).artifactFiles.files
            ArtifactManager.JAVAC -> return variant.scope.javac
            ArtifactManager.MERGED_ASSETS -> return variant.scope.mergedAssets
            ArtifactManager.MERGED_RES -> return variant.scope.mergedRes
            ArtifactManager.MERGED_MANIFESTS -> return variant.scope.mergedManifests
            ArtifactManager.PROCESSED_RES -> return variant.scope.processedRes
            ArtifactManager.SYMBOL_LIST -> return variant.scope.symbolList
            ArtifactManager.SYMBOL_LIST_WITH_PACKAGE_NAME -> return variant.scope.symbolListWithPackageName
        }
        TODO("Unexpected type: $type")
    }

    internal fun doFullTransform() {
        this.inputs.parallelStream().forEach { input ->
            input.directoryInputs.parallelStream().forEach {
                it.file.transform(outputProvider.getContentLocation(it.file.name, it.contentTypes, it.scopes, Format.DIRECTORY)) { bytecode ->
                    bytecode.transform(this)
                }
            }
            input.jarInputs.parallelStream().forEach {
                it.file.transform(outputProvider.getContentLocation(it.name, it.contentTypes, it.scopes, Format.JAR)) { bytecode ->
                    bytecode.transform(this)
                }
            }
        }
    }

    internal fun doIncrementalTransform() {
        this.inputs.parallelStream().forEach { input ->
            input.jarInputs.parallelStream().filter { it.status != Status.NOTCHANGED }.forEach { jarInput ->
                if (Status.REMOVED == jarInput.status || Status.CHANGED == jarInput.status) {
                    jarInput.file.delete()
                }

                if (Status.ADDED == jarInput.status || Status.CHANGED == jarInput.status) {
                    val root = outputProvider.getContentLocation(jarInput.name, jarInput.contentTypes, jarInput.scopes, Format.JAR)
                    jarInput.file.transform(root) { bytecode ->
                        bytecode.transform(this)
                    }
                }
            }

            input.directoryInputs.parallelStream().forEach { dirInput ->
                dirInput.changedFiles.ifNotEmpty {
                    it.forEach { file, status ->
                        if (Status.REMOVED == status || Status.CHANGED == status) {
                            file.delete()
                        }

                        if (Status.ADDED == status || Status.CHANGED == status) {
                            val root = outputProvider.getContentLocation(dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY)
                            val path = file.absolutePath.substring(dirInput.file.absolutePath.length + File.separator.length)
                            file.transform(File(root, path)) { bytecode ->
                                bytecode.transform(this)
                            }
                        }
                    }

                }
            }
        }
    }

    private fun ByteArray.transform(invocation: BoosterTransformInvocation): ByteArray {
        return transformers.fold(this) { bytes, transformer ->
            transformer.transform(invocation, bytes)
        }
    }

}
