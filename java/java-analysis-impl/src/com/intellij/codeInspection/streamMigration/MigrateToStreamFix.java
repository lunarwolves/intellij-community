/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.streamMigration;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInspection.LambdaCanBeMethodReferenceInspection;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.InitializerUsageStatus;
import com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.Operation;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Tagir Valeev
 */
abstract class MigrateToStreamFix implements LocalQuickFix {
  @NotNull
  @Override
  public String getName() {
    return getFamilyName();
  }

  @Override
  public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
    PsiElement element = descriptor.getPsiElement();
    if (element instanceof PsiForeachStatement) {
      PsiForeachStatement foreachStatement = (PsiForeachStatement)element;
      PsiStatement body = foreachStatement.getBody();
      final PsiExpression iteratedValue = foreachStatement.getIteratedValue();
      if (body != null && iteratedValue != null) {
        final PsiParameter parameter = foreachStatement.getIterationParameter();
        StreamApiMigrationInspection.TerminalBlock tb = StreamApiMigrationInspection.TerminalBlock.from(parameter, body);
        if (!FileModificationService.getInstance().preparePsiElementForWrite(foreachStatement)) return;
        List<Operation> operations = tb.extractOperations();
        migrate(project, descriptor, foreachStatement, iteratedValue, body, tb, operations);
      }
    }
  }

  abstract void migrate(@NotNull Project project,
                        @NotNull ProblemDescriptor descriptor,
                        @NotNull PsiForeachStatement foreachStatement,
                        @NotNull PsiExpression iteratedValue,
                        @NotNull PsiStatement body,
                        @NotNull StreamApiMigrationInspection.TerminalBlock tb,
                        @NotNull List<Operation> operations);

  static void replaceWithNumericAddition(@NotNull Project project,
                                         PsiForeachStatement foreachStatement,
                                         PsiVariable var,
                                         StringBuilder builder,
                                         PsiType expressionType) {
    PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
    restoreComments(foreachStatement, foreachStatement.getBody());
    InitializerUsageStatus status = StreamApiMigrationInspection.getInitializerUsageStatus(var, foreachStatement);
    if (status != InitializerUsageStatus.UNKNOWN) {
      PsiExpression initializer = var.getInitializer();
      if (ExpressionUtils.isZero(initializer)) {
        PsiType type = var.getType();
        String replacement = (type.equals(expressionType) ? "" : "(" + type.getCanonicalText() + ") ") + builder;
        replaceInitializer(foreachStatement, var, initializer, replacement, status);
        return;
      }
    }
    PsiElement result =
      foreachStatement.replace(elementFactory.createStatementFromText(var.getName() + "+=" + builder + ";", foreachStatement));
    simplifyAndFormat(project, result);
  }

  static void replaceInitializer(PsiForeachStatement foreachStatement,
                                 PsiVariable var,
                                 PsiExpression initializer,
                                 String replacement,
                                 InitializerUsageStatus status) {
    Project project = foreachStatement.getProject();
    PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
    if(status == InitializerUsageStatus.DECLARED_JUST_BEFORE) {
      initializer.replace(elementFactory.createExpressionFromText(replacement, foreachStatement));
      removeLoop(foreachStatement);
      simplifyAndFormat(project, var);
    } else {
      if(status == InitializerUsageStatus.AT_WANTED_PLACE_ONLY) {
        initializer.delete();
      }
      PsiElement result =
        foreachStatement.replace(elementFactory.createStatementFromText(var.getName() + " = " + replacement + ";", foreachStatement));
      simplifyAndFormat(project, result);
    }
  }

  static void simplifyAndFormat(@NotNull Project project, PsiElement result) {
    if (result == null) return;
    LambdaCanBeMethodReferenceInspection.replaceAllLambdasWithMethodReferences(result);
    CodeStyleManager.getInstance(project).reformat(JavaCodeStyleManager.getInstance(project).shortenClassReferences(result));
  }

  static void restoreComments(PsiForeachStatement foreachStatement, PsiStatement body) {
    final PsiElement parent = foreachStatement.getParent();
    for (PsiElement comment : PsiTreeUtil.findChildrenOfType(body, PsiComment.class)) {
      parent.addBefore(comment, foreachStatement);
    }
  }

  @NotNull
  static StringBuilder generateStream(PsiExpression iteratedValue, List<Operation> intermediateOps) {
    return generateStream(iteratedValue, intermediateOps, false);
  }

  @NotNull
  static StringBuilder generateStream(PsiExpression iteratedValue, List<Operation> intermediateOps, boolean noStreamForEmpty) {
    StringBuilder buffer = new StringBuilder();
    final PsiType iteratedValueType = iteratedValue.getType();
    if (iteratedValueType instanceof PsiArrayType) {
      buffer.append("java.util.Arrays.stream(").append(iteratedValue.getText()).append(")");
    }
    else {
      buffer.append(getIteratedValueText(iteratedValue));
      if (!(noStreamForEmpty && intermediateOps.isEmpty())) {
        buffer.append(".stream()");
      }
    }
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(iteratedValue.getProject());
    intermediateOps.stream().map(op -> op.createReplacement(factory)).forEach(buffer::append);
    return buffer;
  }

  static String getIteratedValueText(PsiExpression iteratedValue) {
    return iteratedValue instanceof PsiCallExpression ||
           iteratedValue instanceof PsiReferenceExpression ||
           iteratedValue instanceof PsiQualifiedExpression ||
           iteratedValue instanceof PsiParenthesizedExpression ? iteratedValue.getText() : "(" + iteratedValue.getText() + ")";
  }

  static void removeLoop(@NotNull PsiForeachStatement statement) {
    PsiElement parent = statement.getParent();
    if (parent instanceof PsiLabeledStatement) {
      parent.delete();
    }
    else {
      statement.delete();
    }
  }
}
