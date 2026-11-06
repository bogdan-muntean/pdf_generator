package com.pdfgen.adapters.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceRegistryTest {

    @TempDir
    Path runDir;

    @Test
    void onlyOneOwnerAtATimeAndTheLockIsReleasedOnClose() {
        try (InstanceRegistry first = new InstanceRegistry(runDir)) {
            assertThat(first.tryLock()).isTrue();
            assertThat(new InstanceRegistry(runDir).tryLock()).isFalse();
        }
        try (InstanceRegistry again = new InstanceRegistry(runDir)) {
            assertThat(again.tryLock()).as("lock released after close").isTrue();
        }
    }

    @Test
    void publishedInstanceIsReadableByOthersAndDeletedOnClose() {
        InstanceRegistry owner = new InstanceRegistry(runDir);
        assertThat(owner.tryLock()).isTrue();
        owner.publish(51234);

        InstanceRegistry other = new InstanceRegistry(runDir);
        assertThat(other.readPublished()).hasValueSatisfying(instance -> {
            assertThat(instance.id()).isEqualTo(owner.instanceId());
            assertThat(instance.port()).isEqualTo(51234);
            assertThat(instance.pid()).isEqualTo(ProcessHandle.current().pid());
            assertThat(instance.url()).isEqualTo("http://127.0.0.1:51234/");
        });

        owner.close();
        assertThat(other.readPublished()).isEmpty();
        owner.close(); // idempotent
    }
}
