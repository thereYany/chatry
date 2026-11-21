package com.wherelee.base.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spring Modulith 模块结构验证。
 *
 * <p>验证各模块 package-info 上声明的 allowedDependencies 与实际依赖一致、模块间无循环依赖，
 * 并固定内核模块清单（防止把垂直业务模块混进内核）。</p>
 */
class ModulithVerificationTest {

    @Test
    void 内核模块声明与实际依赖一致且无循环() {
        ApplicationModules modules = ApplicationModules.of("com.wherelee.base.modules");

        // 六个内核模块必须都在，且 app/垂直模块不得出现
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("platform", "contracts", "identity",
                        "payment", "llm", "metering");

        assertThatCode(modules::verify).doesNotThrowAnyException();
    }

    @Test
    void 模块依赖方向必须符合声明() {
        ApplicationModules modules = ApplicationModules.of("com.wherelee.base.modules");

        // platform 是底层：不能有人反向依赖业务模块
        assertThat(modules.getModuleByName("platform")).isPresent();
        assertThat(modules.getModuleByName("contracts")).isPresent();

        // 六个模块的 allowedDependencies 声明必须与真实依赖一致
        modules.verify();
        assertThat(modules.getModuleByName("payment"))
                .hasValueSatisfying(module -> assertThat(module.getDisplayName()).isEqualTo("Payment"));
    }
}
