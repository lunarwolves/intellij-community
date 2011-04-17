/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package org.jetbrains.plugins.groovy.refactoring.move;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.refactoring.move.moveMembers.MoveJavaMemberHandler;
import com.intellij.refactoring.move.moveMembers.MoveMembersOptions;
import com.intellij.refactoring.move.moveMembers.MoveMembersProcessor;
import com.intellij.refactoring.util.EnumConstantsUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.VisibilityUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.GroovyRecursiveElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariableDeclaration;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrEnumTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrEnumConstant;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrEnumConstantList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMember;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;
import org.jetbrains.plugins.groovy.refactoring.GroovyChangeContextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author Maxim.Medvedev
 */
public class MoveGroovyMemberHandler extends MoveJavaMemberHandler {
  @Override
  public boolean changeExternalUsage(@NotNull MoveMembersOptions options, @NotNull MoveMembersProcessor.MoveMembersUsageInfo usage) {
    final PsiElement element = usage.getElement();
    if (element == null || !element.isValid()) return true;

    if (usage.reference instanceof GrReferenceExpression) {
      GrReferenceExpression refExpr = (GrReferenceExpression)usage.reference;
      GrExpression qualifier = refExpr.getQualifierExpression();
      if (qualifier != null) {
        if (usage.qualifierClass != null) {
          changeQualifier(refExpr, usage.qualifierClass, usage.member);
        }
        else {
          refExpr.setQualifier(null);
        }
      }
      else { // no qualifier
        if (usage.qualifierClass != null) {
          changeQualifier(refExpr, usage.qualifierClass, usage.member);
        }
      }
      return true;
    }
    return false;
  }

  @Override
  @NotNull
  public PsiMember doMove(@NotNull MoveMembersOptions options, @NotNull PsiMember member, PsiElement anchor, @NotNull PsiClass targetClass) {
    GroovyChangeContextUtil.encodeContextInfo(member);

    PsiMember memberCopy;
    if (options.makeEnumConstant() &&
        member instanceof GrVariable &&
        EnumConstantsUtil.isSuitableForEnumConstant(((PsiVariable)member).getType(), targetClass)) {
      memberCopy = createEnumConstant(member.getName(), ((GrVariable)member).getInitializerGroovy(), member.getProject());
      member.delete();
      memberCopy = (PsiMember)addEnumConstant(targetClass, (GrEnumConstant)memberCopy, anchor);
    }
    else if (member instanceof GrEnumConstant) {
      memberCopy = (PsiMember)member.copy();
      member.delete();
      memberCopy = (PsiMember)addEnumConstant(targetClass, (GrEnumConstant)memberCopy, null);
    }
    else if (member instanceof GrField) {
      GrVariableDeclaration parentCopy;
      final GrVariableDeclaration parent = (GrVariableDeclaration)member.getParent();
      int number = findMemberNumber(parent.getMembers(), (GrField)member);
      parentCopy = (GrVariableDeclaration)parent.copy();
      final GrMember[] members = parentCopy.getMembers();
      for (int i = 0; i < number; i++) {
        members[i].delete();
      }
      for (int i = number + 1; i < members.length; i++) {
        members[i].delete();
      }
      memberCopy = parentCopy.getMembers()[0];

      if (member.getContainingClass().isInterface() && !targetClass.isInterface()) {
        //might need to make modifiers explicit, see IDEADEV-11416
        final PsiModifierList list = memberCopy.getModifierList();
        assert list != null;
        VisibilityUtil.setVisibility(list, VisibilityUtil.getVisibilityModifier(member.getModifierList()));
        list.setModifierProperty(PsiModifier.STATIC, member.hasModifierProperty(PsiModifier.STATIC));
        list.setModifierProperty(PsiModifier.FINAL, member.hasModifierProperty(PsiModifier.FINAL));
      }
      member.delete();
      if (anchor != null) anchor = anchor.getParent();
      parentCopy = (GrVariableDeclaration)targetClass.addAfter(parentCopy, anchor);
      return parentCopy.getMembers()[0];
    }
    else if (member instanceof GrMethod) {
      memberCopy = (PsiMember)member.copy();
      if (member.getContainingClass().isInterface() && !targetClass.isInterface()) {
        //might need to make modifiers explicit, see IDEADEV-11416
        final PsiModifierList list = memberCopy.getModifierList();
        assert list != null;
        list.setModifierProperty(PsiModifier.STATIC, member.hasModifierProperty(PsiModifier.STATIC));
        list.setModifierProperty(PsiModifier.FINAL, member.hasModifierProperty(PsiModifier.FINAL));
        VisibilityUtil.setVisibility(list, VisibilityUtil.getVisibilityModifier(member.getModifierList()));
      }

      member.delete();
      memberCopy = (PsiMember)targetClass.addAfter(memberCopy, anchor);
    }
    else {
      memberCopy = (PsiMember)member.copy();
      member.delete();
      memberCopy = (PsiMember)targetClass.addAfter(memberCopy, anchor);
    }
    return memberCopy;
  }

  @Override
  public void decodeContextInfo(@NotNull PsiElement scope) {
    GroovyChangeContextUtil.decodeContextInfo(scope, null, null);
  }

  private static int findMemberNumber(PsiMember[] members, GrMember member) {
    for (int i = 0; i < members.length; i++) {
      if (members[i].equals(member)) return i;
    }
    return -1;
  }

  private static void changeQualifier(GrReferenceExpression refExpr, PsiClass aClass, PsiMember member) throws IncorrectOperationException {
    if (hasOnDemandStaticImport(refExpr, aClass)) {
      refExpr.setQualifier(null);
    }
    else if (!hasStaticImport(refExpr, member)) {
      GroovyPsiElementFactory factory = GroovyPsiElementFactory.getInstance(refExpr.getProject());

      refExpr.setQualifier(factory.createReferenceExpressionFromText(aClass.getName()));
      ((GrReferenceExpression)refExpr.getQualifierExpression()).bindToElement(aClass);
    }
  }

  private static boolean hasStaticImport(GrReferenceExpression refExpr, PsiMember member) {
    if (!(refExpr.getContainingFile() instanceof GroovyFile)) return false;

    final GrImportStatement[] imports = ((GroovyFile)refExpr.getContainingFile()).getImportStatements();
    for (GrImportStatement stmt : imports) {
      if (!stmt.isOnDemand() && stmt.resolveTargetClass() == member.getContainingClass() &&
          Comparing.strEqual(stmt.getImportReference().getReferenceName(), member.getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasOnDemandStaticImport(final PsiElement element, final PsiClass aClass) {
    if (element.getContainingFile() instanceof GroovyFile) {
      final GrImportStatement[] importStatements = ((GroovyFile)element.getContainingFile()).getImportStatements();
      for (GrImportStatement stmt : importStatements) {
        final GrCodeReferenceElement ref = stmt.getImportReference();
        if (ref != null && stmt.isStatic() && stmt.isOnDemand() && ref.resolve() == aClass) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  @Nullable
  public PsiElement getAnchor(@NotNull final PsiMember member, @NotNull final PsiClass targetClass) {
    if (member instanceof GrField && member.hasModifierProperty(PsiModifier.STATIC)) {
      final List<PsiField> referencedFields = new ArrayList<PsiField>();
      final GrExpression psiExpression = ((GrField)member).getInitializerGroovy();
      if (psiExpression != null) {
        psiExpression.accept(new GroovyRecursiveElementVisitor() {
          @Override
          public void visitReferenceExpression(final GrReferenceExpression expression) {
            super.visitReferenceExpression(expression);
            final PsiElement psiElement = expression.resolve();
            if (psiElement instanceof GrField) {
              final GrField grField = (GrField)psiElement;
              if (grField.getContainingClass() == targetClass && !referencedFields.contains(grField)) {
                referencedFields.add(grField);
              }
            }
          }
        });
      }
      if (!referencedFields.isEmpty()) {
        Collections.sort(referencedFields, new Comparator<PsiField>() {
          public int compare(final PsiField o1, final PsiField o2) {
            return -PsiUtilBase.compareElementsByPosition(o1, o2);
          }
        });
        return referencedFields.get(0);
      }
    }
    return null;
  }

  private static GrEnumConstant createEnumConstant(String constantName, GrExpression initializerExpr, Project project)
    throws IncorrectOperationException {
    final GroovyPsiElementFactory elementFactory = GroovyPsiElementFactory.getInstance(project);
    final String enumConstantText = initializerExpr != null ? constantName + "(" + initializerExpr.getText() + ")" : constantName;
    return elementFactory.createEnumConstantFromText(enumConstantText);
  }

  private static PsiElement addEnumConstant(PsiClass targetClass, GrEnumConstant constant, @Nullable PsiElement anchor) {
    if (targetClass instanceof GrEnumTypeDefinition) {
      final GrEnumTypeDefinition enumeration = (GrEnumTypeDefinition)targetClass;
      final GrEnumConstantList constantList = enumeration.getEnumConstantList();
      if (constantList != null) {
        ASTNode node = constantList.getNode();
        node.addLeaf(GroovyTokenTypes.mCOMMA, ",", node.getFirstChildNode());
        return constantList.addBefore(constant, constantList.getFirstChild());
      }
      else {
        final PsiElement parent = constant.getParent();
        assert parent instanceof GrEnumConstantList;
        final GrEnumConstantList constListCopy = ((GrEnumConstantList)targetClass.add(parent));
        return constListCopy.getEnumConstants()[0];
      }
    }
    return (anchor != null ? targetClass.addAfter(constant, anchor) : targetClass.add(constant));
  }
}
