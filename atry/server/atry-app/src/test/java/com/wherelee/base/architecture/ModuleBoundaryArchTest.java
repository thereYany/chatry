package com.wherelee.base.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束测试（重建被删除的架构门禁）。
 *
 * <p>规则来源于模块 pom 依赖图与代码现状，覆盖五条红线：</p>
 * <ol>
 *   <li>platform 是内核最底层，不得反向依赖任何业务模块；</li>
 *   <li>contracts 只放契约（接口/记录/枚举），不依赖业务实现、不带 Spring 组件注解；</li>
 *   <li>身份/支付/LLM/计量四个业务模块之间不得互相依赖（只能通过 contracts）；</li>
 *   <li>内核模块不得依赖宿主 app/bootstrap（保证内核可独立部署）；</li>
 *   <li>模块包之间无循环依赖，Controller 不得直连 JdbcTemplate（避免绕过领域服务与事务边界）。</li>
 * </ol>
 */
class ModuleBoundaryArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.wherelee.base");
    }

    private static ArchRule noDependencyFrom(String fromPackage, String... targetPackages) {
        return noClasses()
                .that().resideInAPackage(fromPackage)
                .should().dependOnClassesThat().resideInAnyPackage(targetPackages);
    }

    @Test
    void platform不得依赖任何业务模块与宿主() {
        noDependencyFrom("com.wherelee.base.modules.platform..",
                "com.wherelee.base.modules.identity..",
                "com.wherelee.base.modules.payment..",
                "com.wherelee.base.modules.llm..",
                "com.wherelee.base.modules.metering..",
                "com.wherelee.base.app..",
                "com.wherelee.base.bootstrap..")
                .check(classes);
    }

    @Test
    void contracts只依赖platform且不反向依赖业务实现() {
        noDependencyFrom("com.wherelee.base.modules.contracts..",
                "com.wherelee.base.modules.identity..",
                "com.wherelee.base.modules.payment..",
                "com.wherelee.base.modules.llm..",
                "com.wherelee.base.modules.metering..",
                "com.wherelee.base.app..",
                "com.wherelee.base.bootstrap..")
                .check(classes);
    }

    @Test
    void 身份模块不得依赖支付LLM计量与宿主() {
        noDependencyFrom("com.wherelee.base.modules.identity..",
                "com.wherelee.base.modules.payment..",
                "com.wherelee.base.modules.llm..",
                "com.wherelee.base.modules.metering..",
                "com.wherelee.base.app..",
                "com.wherelee.base.bootstrap..")
                .check(classes);
    }

    @Test
    void 支付模块不得依赖身份LLM计量与宿主() {
        noDependencyFrom("com.wherelee.base.modules.payment..",
                "com.wherelee.base.modules.identity..",
                "com.wherelee.base.modules.llm..",
                "com.wherelee.base.modules.metering..",
                "com.wherelee.base.app..",
                "com.wherelee.base.bootstrap..")
                .check(classes);
    }

    @Test
    void LLM模块不得依赖身份支付计量与宿主() {
        noDependencyFrom("com.wherelee.base.modules.llm..",
                "com.wherelee.base.modules.identity..",
                "com.wherelee.base.modules.payment..",
                "com.wherelee.base.modules.metering..",
                "com.wherelee.base.app..",
                "com.wherelee.base.bootstrap..")
                .check(classes);
    }

    @Test
    void 计量模块不得依赖身份支付LLM与宿主() {
        noDependencyFrom("com.wherelee.base.modules.metering..",
                "com.wherelee.base.modules.identity..",
                "com.wherelee.base.modules.payment..",
                "com.wherelee.base.modules.llm..",
                "com.wherelee.base.app..",
                "com.wherelee.base.bootstrap..")
                .check(classes);
    }

    @Test
    void 内核模块之间不得出现循环依赖() {
        SlicesRuleDefinition.slices()
                .matching("com.wherelee.base.modules.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    @Test
    void contracts中不得出现Spring组件注解() {
        // 契约模块只允许接口/记录/DTO：出现 @Service/@Component/@Repository 说明把实现塞进了契约
        noClasses().that().resideInAPackage("com.wherelee.base.modules.contracts..")
                .should().beAnnotatedWith(Service.class).check(classes);
        noClasses().that().resideInAPackage("com.wherelee.base.modules.contracts..")
                .should().beAnnotatedWith(Component.class).check(classes);
        noClasses().that().resideInAPackage("com.wherelee.base.modules.contracts..")
                .should().beAnnotatedWith(Repository.class).check(classes);
        noClasses().that().resideInAPackage("com.wherelee.base.modules.contracts..")
                .should().beAnnotatedWith(RestController.class).check(classes);
    }

    @Test
    void Controller必须标注RestController且不得直连JdbcTemplate() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .check(classes);

        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .check(classes);
    }

    @Test
    void Repository命名必须标注Repository注解() {
        classes().that().haveSimpleNameEndingWith("Repository")
                .and().resideInAPackage("com.wherelee.base..")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(Repository.class)
                .check(classes);
    }

    @Test
    void Service命名必须有Spring组件语义() {
        classes().that().haveSimpleNameEndingWith("Service")
                .and().resideInAPackage("com.wherelee.base..")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(Service.class)
                .orShould().beAnnotatedWith(Component.class)
                .check(classes);
    }

    @Test
    void Aspect必须标注Aspect注解() {
        classes().that().haveSimpleNameEndingWith("Aspect")
                .and().resideInAPackage("com.wherelee.base..")
                .and().areNotInterfaces()
                .should().beAnnotatedWith(org.aspectj.lang.annotation.Aspect.class)
                .check(classes);
    }
}
