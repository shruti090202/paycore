package com.paycore;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The modular-monolith contract, enforced at build time.
 * <p>
 * Packages directly under {@code com.paycore} are modules. Rules:
 * <ol>
 *   <li>Modules never depend on {@code api} (controllers are the outermost layer).</li>
 *   <li>{@code common} depends on no other module.</li>
 *   <li>No dependency cycles between modules — the single most useful rule: it forces every
 *       cross-module call to go one direction and makes extraction into a service mechanical.</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.paycore", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modules_do_not_depend_on_api_layer =
            noClasses().that().resideOutsideOfPackage("..paycore.api..")
                    .should().dependOnClassesThat().resideInAPackage("..paycore.api..");

    @ArchTest
    static final ArchRule common_depends_on_nothing_else =
            noClasses().that().resideInAPackage("..paycore.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..paycore.auth..", "..paycore.merchant..", "..paycore.payments..", "..paycore.ledger..",
                            "..paycore.banksim..", "..paycore.idempotency..", "..paycore.ratelimit..",
                            "..paycore.webhooks..", "..paycore.risk..", "..paycore.reconciliation..", "..paycore.jobs..");

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.paycore.(*)..").should().beFreeOfCycles();
}
