/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.gradle

import nebula.plugin.extraconfigurations.ProvidedBasePlugin
import org.elasticsearch.gradle.precommit.PrecommitTasks
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.maven.MavenPom
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.internal.jvm.Jvm
import org.gradle.util.GradleVersion

/**
 * Encapsulates build configuration for elasticsearch projects.
 */
class BuildPlugin implements Plugin<Project> {

    static final JavaVersion minimumJava = JavaVersion.VERSION_1_8

    @Override
    void apply(Project project) {
        project.pluginManager.apply('java')
        project.pluginManager.apply('carrotsearch.randomized-testing')
        // these plugins add lots of info to our jars
        project.pluginManager.apply('nebula.info-broker')
        project.pluginManager.apply('nebula.info-basic')
        project.pluginManager.apply('nebula.info-java')
        project.pluginManager.apply('nebula.info-scm')
        project.pluginManager.apply('nebula.info-jar')
        project.pluginManager.apply('com.bmuschko.nexus')
        project.pluginManager.apply(ProvidedBasePlugin)

        globalBuildInfo(project)
        configureRepositories(project)
        configureConfigurations(project)
        project.ext.versions = VersionProperties.versions
        configureCompile(project)
        configureJarManifest(project)
        configureTest(project)
        PrecommitTasks.configure(project)
    }

    static void globalBuildInfo(Project project) {
        if (project.rootProject.ext.has('buildChecksDone') == false) {
            String javaHome = System.getenv('JAVA_HOME')

            // Build debugging info
            println '======================================='
            println 'Elasticsearch Build Hamster says Hello!'
            println '======================================='
            println "  Gradle Version : ${project.gradle.gradleVersion}"
            println "  JDK Version    : ${System.getProperty('java.runtime.version')} (${System.getProperty('java.vendor')})"
            println "  JAVA_HOME      : ${javaHome == null ? 'not set' : javaHome}"
            println "  OS Info        : ${System.getProperty('os.name')} ${System.getProperty('os.version')} (${System.getProperty('os.arch')})"

            // enforce gradle version
            GradleVersion minGradle = GradleVersion.version('2.8')
            if (GradleVersion.current() < minGradle) {
                throw new GradleException("${minGradle} or above is required to build elasticsearch")
            }

            // enforce Java version
            if (JavaVersion.current() < minimumJava) {
                throw new GradleException("Java ${minimumJava} or above is required to build Elasticsearch")
            }

            // find java home so eg tests can use it to set java to run with
            if (javaHome == null) {
                if (System.getProperty("idea.active") != null) {
                    // intellij doesn't set JAVA_HOME, so we use the jdk gradle was run with
                    javaHome = Jvm.current().javaHome
                } else {
                    throw new GradleException('JAVA_HOME must be set to build Elasticsearch')
                }
            }
            project.rootProject.ext.javaHome = javaHome
            project.rootProject.ext.buildChecksDone = true
        }
        project.targetCompatibility = minimumJava
        project.sourceCompatibility = minimumJava
        // set java home for each project, so they dont have to find it in the root project
        project.ext.javaHome = project.rootProject.ext.javaHome
    }

    /** Return the name
     */
    static String transitiveDepConfigName(String groupId, String artifactId, String version) {
        return "_transitive_${groupId}:${artifactId}:${version}"
    }

    /**
     * Makes dependencies non-transitive.
     *
     * Gradle allows setting all dependencies as non-transitive very easily.
     * Sadly this mechanism does not translate into maven pom generation. In order
     * to effectively make the pom act as if it has no transitive dependencies,
     * we must exclude each transitive dependency of each direct dependency.
     *
     * Determining the transitive deps of a dependency which has been resolved as
     * non-transitive is difficult because the process of resolving removes the
     * transitive deps. To sidestep this issue, we create a configuration per
     * direct dependency version. This specially named and unique configuration
     * will contain all of the transitive dependencies of this particular
     * dependency. We can then use this configuration during pom generation
     * to iterate the transitive dependencies and add excludes.
     */
    static void configureConfigurations(Project project) {
        // fail on any conflicting dependency versions
        project.configurations.all({ Configuration configuration ->
            if (configuration.name.startsWith('_transitive_')) {
                // don't force transitive configurations to not conflict with themselves, since
                // we just have them to find *what* transitive deps exist
                return
            }
            configuration.resolutionStrategy.failOnVersionConflict()
        })

        // force all dependencies added directly to compile/testCompile to be non-transitive, except for ES itself
        Closure disableTransitiveDeps = { ModuleDependency dep ->
            if (!(dep instanceof ProjectDependency) && dep.getGroup() != 'org.elasticsearch') {
                dep.transitive = false

                // also create a configuration just for this dependency version, so that later
                // we can determine which transitive dependencies it has
                String depConfig = transitiveDepConfigName(dep.group, dep.name, dep.version)
                if (project.configurations.findByName(depConfig) == null) {
                    project.configurations.create(depConfig)
                    project.dependencies.add(depConfig, "${dep.group}:${dep.name}:${dep.version}")
                }
            }
        }

        project.configurations.compile.dependencies.all(disableTransitiveDeps)
        project.configurations.testCompile.dependencies.all(disableTransitiveDeps)
        project.configurations.provided.dependencies.all(disableTransitiveDeps)

        // add exclusions to the pom directly, for each of the transitive deps of this project's deps
        project.modifyPom { MavenPom pom ->
            pom.withXml { XmlProvider xml ->
                // first find if we have dependencies at all, and grab the node
                NodeList depsNodes = xml.asNode().get('dependencies')
                if (depsNodes.isEmpty()) {
                    return
                }

                // check each dependency for any transitive deps
                for (Node depNode : depsNodes.get(0).children()) {
                    String groupId = depNode.get('groupId').get(0).text()
                    String artifactId = depNode.get('artifactId').get(0).text()
                    String version = depNode.get('version').get(0).text()

                    // collect the transitive deps now that we know what this dependency is
                    String depConfig = transitiveDepConfigName(groupId, artifactId, version)
                    Configuration configuration = project.configurations.findByName(depConfig)
                    if (configuration == null) {
                        continue // we did not make this dep non-transitive
                    }
                    Set<ResolvedArtifact> artifacts = configuration.resolvedConfiguration.resolvedArtifacts
                    if (artifacts.size() <= 1) {
                        // this dep has no transitive deps (or the only artifact is itself)
                        continue
                    }

                    // we now know we have something to exclude, so add the exclusion elements
                    Node exclusions = depNode.appendNode('exclusions')
                    for (ResolvedArtifact transitiveArtifact : artifacts) {
                        ModuleVersionIdentifier transitiveDep = transitiveArtifact.moduleVersion.id
                        if (transitiveDep.group == groupId && transitiveDep.name == artifactId) {
                            continue; // don't exclude the dependency itself!
                        }
                        Node exclusion = exclusions.appendNode('exclusion')
                        exclusion.appendNode('groupId', transitiveDep.group)
                        exclusion.appendNode('artifactId', transitiveDep.name)
                    }
                }
            }
        }
    }

    /** Adds repositores used by ES dependencies */
    static void configureRepositories(Project project) {
        RepositoryHandler repos = project.repositories
        repos.mavenCentral()
        repos.maven {
            name 'sonatype-snapshots'
            url 'http://oss.sonatype.org/content/repositories/snapshots/'
        }
        String luceneVersion = VersionProperties.lucene
        if (luceneVersion.contains('-snapshot')) {
            // extract the revision number from the version with a regex matcher
            String revision = (luceneVersion =~ /\w+-snapshot-(\d+)/)[0][1]
            repos.maven {
                name 'lucene-snapshots'
                url "http://s3.amazonaws.com/download.elasticsearch.org/lucenesnapshots/${revision}"
            }
        }
    }

    /** Adds compiler settings to the project */
    static void configureCompile(Project project) {
        project.afterEvaluate {
            // fail on all javac warnings
            project.tasks.withType(JavaCompile) {
                options.compilerArgs << '-Werror' << '-Xlint:all' << '-Xdoclint:all/private' << '-Xdoclint:-missing'
                options.encoding = 'UTF-8'
            }
        }
    }

    /** Adds additional manifest info to jars */
    static void configureJarManifest(Project project) {
        project.afterEvaluate {
            project.tasks.withType(Jar) { Jar jarTask ->
                manifest {
                    attributes('X-Compile-Elasticsearch-Version': VersionProperties.elasticsearch,
                               'X-Compile-Lucene-Version': VersionProperties.lucene)
                }
            }
        }
    }

    /** Returns a closure of common configuration shared by unit and integration tests. */
    static Closure commonTestConfig(Project project) {
        return {
            jvm "${project.javaHome}/bin/java"
            parallelism System.getProperty('tests.jvms', 'auto')

            // TODO: why are we not passing maxmemory to junit4?
            jvmArg '-Xmx' + System.getProperty('tests.heap.size', '512m')
            jvmArg '-Xms' + System.getProperty('tests.heap.size', '512m')
            if (JavaVersion.current().isJava7()) {
                // some tests need a large permgen, but that only exists on java 7
                jvmArg '-XX:MaxPermSize=128m'
            }
            jvmArg '-XX:MaxDirectMemorySize=512m'
            jvmArg '-XX:+HeapDumpOnOutOfMemoryError'
            File heapdumpDir = new File(project.buildDir, 'heapdump')
            heapdumpDir.mkdirs()
            jvmArg '-XX:HeapDumpPath=' + heapdumpDir
            argLine System.getProperty('tests.jvm.argline')

            // we use './temp' since this is per JVM and tests are forbidden from writing to CWD
            systemProperty 'java.io.tmpdir', './temp'
            systemProperty 'java.awt.headless', 'true'
            systemProperty 'tests.maven', 'true' // TODO: rename this once we've switched to gradle!
            systemProperty 'tests.artifact', project.name
            systemProperty 'tests.task', path
            systemProperty 'tests.security.manager', 'true'
            // default test sysprop values
            systemProperty 'tests.ifNoTests', 'fail'
            systemProperty 'es.logger.level', 'WARN'
            for (Map.Entry<String, String> property : System.properties.entrySet()) {
                if (property.getKey().startsWith('tests.') ||
                    property.getKey().startsWith('es.')) {
                    systemProperty property.getKey(), property.getValue()
                }
            }

            // System assertions (-esa) are disabled for now because of what looks like a
            // JDK bug triggered by Groovy on JDK7. We should look at re-enabling system
            // assertions when we upgrade to a new version of Groovy (currently 2.4.4) or
            // require JDK8. See https://issues.apache.org/jira/browse/GROOVY-7528.
            enableSystemAssertions false

            testLogging {
                slowTests {
                    heartbeat 10
                    summarySize 5
                }
                stackTraceFilters {
                    // custom filters: we carefully only omit test infra noise here
                    contains '.SlaveMain.'
                    regex(/^(\s+at )(org\.junit\.)/)
                    // also includes anonymous classes inside these two:
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.RandomizedRunner)/)
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.ThreadLeakControl)/)
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.rules\.)/)
                    regex(/^(\s+at )(org\.apache\.lucene\.util\.TestRule)/)
                    regex(/^(\s+at )(org\.apache\.lucene\.util\.AbstractBeforeAfterRule)/)
                }
                outputMode System.getProperty('tests.output', 'onerror')
            }

            balancers {
                executionTime cacheFilename: ".local-${project.version}-${name}-execution-times.log"
            }

            listeners {
                junitReport()
            }

            exclude '**/*$*.class'
        }
    }

    /** Configures the test task */
    static Task configureTest(Project project) {
        Task test = project.tasks.getByName('test')
        test.configure(commonTestConfig(project))
        test.configure {
            include '**/*Tests.class'
        }
        return test
    }
}
