package org.csajava.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class BuildProfileTest {
    @Test
    public void copiesTheSelectedVersionedProfileIntoTheImage() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String build = Files.readString(Path.of("build_csa-java.sh"));

        assertTrue(dockerfile.contains("ARG TAG=vq"));
        assertTrue(dockerfile.contains("COPY profiles/${TAG}.yaml /config.yaml"));
        assertTrue(build.contains("docker build --build-arg TAG=\"$TAG\""));
    }

    @Test
    public void configuresTheJvmForShortLivedProcessesWithinOneCpu() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(dockerfile.contains(
                "ENV CSA_JAVA_OPTS=\"-XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1\""));
    }

    @Test
    public void omitsUnusedKubernetesClientRuntimes() {
        String classpath = System.getProperty("java.class.path");

        for (String unusedRuntime : new String[] {
            "/sts-",
            "/aws-core-",
            "/aws-query-protocol-",
            "/sdk-core-",
            "/netty-nio-client-",
            "/google-auth-library-",
            "/simpleclient-",
            "/client-java-proto-",
            "/protobuf-java-",
            "/bcpkix-",
            "/bcprov-",
            "/bcutil-"
        }) {
            assertFalse(unusedRuntime, classpath.contains(unusedRuntime));
        }
    }

    @Test
    public void rejectsUnknownProfileBeforeCallingDocker() throws Exception {
        Path work = Files.createTempDirectory("csa-java-build-profile");
        Path commands = Files.createDirectory(work.resolve("bin"));
        executable(commands.resolve("docker"));
        executable(commands.resolve("envsubst"));

        ProcessBuilder builder = new ProcessBuilder(Path.of("build_csa-java.sh").toAbsolutePath().toString())
                .directory(work.toFile())
                .redirectErrorStream(true);
        builder.environment().put("TAG", "invalid");
        builder.environment().put("PATH", commands.toString());
        Process process = builder.start();
        process.getOutputStream().close();
        process.info();
        int exitCode = process.waitFor();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(2, exitCode);
        assertTrue(output.contains("TAG must be one of: h, hq, v, vq"));
    }

    private static void executable(Path path) throws Exception {
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        assertTrue(path.toFile().setExecutable(true));
    }
}
