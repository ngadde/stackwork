package org.stackwork.gradle.docker.tasks

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.stackwork.gradle.docker.StackworkExtension
import org.stackwork.gradle.docker.StackworkObject

import static org.stackwork.gradle.docker.ModuleType.BUILDER_IMAGE

class ExecutableImageRunner {

  static void runTestImage(Project composeProject, Project testImageProject) {
    try {
      runExecutableImage(composeProject, testImageProject)
    } finally {
      removeContainer(composeProject, testImageProject)
    }
  }

  static String runBuildImage(Project buildComposeProject, List<Project> builderImageProjects) {
    try {
      builderImageProjects.each { builderImageProject ->
        runExecutableImage(buildComposeProject, builderImageProject)
      }

      OutputStream mainServiceContainerIdOutput = new ByteArrayOutputStream()
      StackworkObject buildComposeStackwork = stackworkFor buildComposeProject
      buildComposeProject.exec {
        setCommandLine(['docker-compose', '-f', buildComposeStackwork.buildDockerComposeRunner.composeFilePath,
                        '-p', buildComposeStackwork.buildDockerComposeRunner.projectId, 'ps', '-q',
                        buildComposeProject.name])
        setStandardOutput mainServiceContainerIdOutput
      }
      def buildContainerId = mainServiceContainerIdOutput.toString().trim()

      buildComposeProject.exec {
        setCommandLine(['docker', 'stop', buildContainerId])
      }

      OutputStream mainServiceImageIdOutput = new ByteArrayOutputStream()
      buildComposeProject.exec {
        setCommandLine(['docker', 'commit', buildContainerId])
        setStandardOutput mainServiceImageIdOutput
      }
      return mainServiceImageIdOutput.toString().trim()
    } finally {
      builderImageProjects.each { builderImageProject ->
        removeContainer(buildComposeProject, builderImageProject)
      }
    }
  }

  private static List runExecutableImage(Project composeProject, Project executableImageProject) {
    StackworkObject composeProjectStackwork = stackworkFor(composeProject)
    StackworkObject executableImageProjectStackwork = stackworkFor(executableImageProject)

    OutputStream containerNameOutput = new ByteArrayOutputStream()
    def runner = executableImageProject.extensions.findByType(StackworkExtension).moduleType == BUILDER_IMAGE ?
        composeProjectStackwork.buildDockerComposeRunner :
        composeProjectStackwork.dockerComposeRunner
    composeProject.exec {
      setCommandLine(['docker-compose', '-f', runner.composeFilePath, '-p', runner.projectId, 'run',
                      '-d', executableImageProject.name])
      setStandardOutput containerNameOutput
    }
    String containerName = containerNameOutput.toString().trim()
    executableImageProjectStackwork.containerId = containerName

    composeProject.exec {
      setCommandLine(['docker', 'logs', '-f', executableImageProjectStackwork.containerId])
    }

    OutputStream exitCodeOutput = new ByteArrayOutputStream()
    composeProject.exec {
      setCommandLine(['docker', 'inspect', '-f=\'{{.State.ExitCode}}\'', executableImageProjectStackwork.containerId])
      setStandardOutput exitCodeOutput
    }
    int exitCode = exitCodeOutput.toString().trim() as int

    if (exitCode != 0) {
      throw new GradleException("Executable (i.e. test- or builder-) image '${containerName}' " +
          "exited with code '${exitCode}'")
    }
  }

  private static void removeContainer(Project composeProject, Project executableImageProject) {
    if (executableImageProject.extensions.getByType(StackworkExtension).stopContainers) {
      StackworkObject executableImageProjectStackwork = stackworkFor(executableImageProject)
      composeProject.exec {
        setCommandLine(['docker', 'rm', '-f', executableImageProjectStackwork.containerId])
      }
    }
  }

  private static StackworkObject stackworkFor(Project project) {
    project.stackwork as StackworkObject
  }

}
