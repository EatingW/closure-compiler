/*
 * Copyright 2008 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.collect.Sets;
import com.google.javascript.jscomp.NodeTraversal.AbstractShallowCallback;
import com.google.javascript.jscomp.ReferenceCollectingCallback.BasicBlock;
import com.google.javascript.jscomp.ReferenceCollectingCallback.Behavior;
import com.google.javascript.jscomp.ReferenceCollectingCallback.Reference;
import com.google.javascript.jscomp.ReferenceCollectingCallback.ReferenceCollection;
import com.google.javascript.jscomp.ReferenceCollectingCallback.ReferenceMap;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks variables to see if they are referenced before their declaration, or
 * if they are redeclared in a way that is suspicious (i.e. not dictated by
 * control structures). This is a more aggressive version of {@link VarCheck},
 * but it lacks the cross-module checks.
 *
 * @author kushal@google.com (Kushal Dave)
 */
class VariableReferenceCheck implements HotSwapCompilerPass {

  static final DiagnosticType EARLY_REFERENCE =
      DiagnosticType.warning(
          "JSC_REFERENCE_BEFORE_DECLARE", "Variable referenced before declaration: {0}");

  static final DiagnosticType REDECLARED_VARIABLE =
      DiagnosticType.warning("JSC_REDECLARED_VARIABLE", "Redeclared variable: {0}");

  static final DiagnosticType AMBIGUOUS_FUNCTION_DECL =
      DiagnosticType.error("AMBIGUOUS_FUNCTION_DECL", "Ambiguous use of a named function: {0}.");

  static final DiagnosticType EARLY_REFERENCE_ERROR =
      DiagnosticType.error(
          "JSC_REFERENCE_BEFORE_DECLARE_ERROR",
          "Illegal variable reference before declaration: {0}");

  static final DiagnosticType REASSIGNED_CONSTANT =
      DiagnosticType.error("JSC_REASSIGNED_CONSTANT", "Constant reassigned: {0}");

  static final DiagnosticType REDECLARED_VARIABLE_ERROR =
      DiagnosticType.error("JSC_REDECLARED_VARIABLE_ERROR", "Illegal redeclared variable: {0}");

  static final DiagnosticType DECLARATION_NOT_DIRECTLY_IN_BLOCK =
      DiagnosticType.error(
          "JSC_DECLARATION_NOT_DIRECTLY_IN_BLOCK",
          "Block-scoped declaration not directly within block: {0}");

  static final DiagnosticType UNUSED_LOCAL_ASSIGNMENT =
      DiagnosticType.disabled(
          "JSC_UNUSED_LOCAL_ASSIGNMENT", "Value assigned to local variable {0} is never read");

  private final AbstractCompiler compiler;

  // If true, the pass will only check code that is at least ES6. Certain errors in block-scoped
  // variable declarations will prevent correct transpilation, so this pass must be run.
  private final boolean forTranspileOnly;

  // NOTE(nicksantos): It's a lot faster to use a shared Set that
  // we clear after each method call, because the Set never gets too big.
  private final Set<BasicBlock> blocksWithDeclarations = new HashSet<>();

  // These types do not permit a block-scoped declaration inside them without an explicit block.
  // e.g. if (b) let x;
  private static final Set<Token> BLOCKLESS_DECLARATION_FORBIDDEN_STATEMENTS =
      Sets.immutableEnumSet(Token.IF, Token.FOR, Token.FOR_IN, Token.FOR_OF, Token.WHILE);

  public VariableReferenceCheck(AbstractCompiler compiler) {
    this(compiler, false);
  }

  VariableReferenceCheck(AbstractCompiler compiler, boolean forTranspileOnly) {
    this.compiler = compiler;
    this.forTranspileOnly = forTranspileOnly;
  }

  private boolean shouldProcess(Node root) {
    if (!forTranspileOnly) {
      return true;
    }
    if (compiler.getOptions().getLanguageIn().isEs6OrHigher()) {
      for (Node singleRoot : root.children()) {
        if (TranspilationPasses.isScriptEs6ImplOrHigher(singleRoot)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void process(Node externs, Node root) {
    if (shouldProcess(root)) {
      new ReferenceCollectingCallback(
              compiler, new ReferenceCheckingBehavior(), new Es6SyntacticScopeCreator(compiler))
          .process(externs, root);
    }
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    if (!forTranspileOnly
        || (compiler.getOptions().getLanguageIn().isEs6OrHigher()
            && TranspilationPasses.isScriptEs6ImplOrHigher(scriptRoot))) {
      new ReferenceCollectingCallback(
              compiler, new ReferenceCheckingBehavior(), new Es6SyntacticScopeCreator(compiler))
          .hotSwapScript(scriptRoot, originalRoot);
    }
  }

  /**
   * Behavior that checks variables for redeclaration or early references
   * just after they go out of scope.
   */
  private class ReferenceCheckingBehavior implements Behavior {

    private Set<String> varsInFunctionBody;

    private ReferenceCheckingBehavior() {
      varsInFunctionBody = new HashSet<>();
    }

    @Override
    public void afterExitScope(NodeTraversal t, ReferenceMap referenceMap) {
      // TODO(bashir) In hot-swap version this means that for global scope we
      // only go through all global variables accessed in the modified file not
      // all global variables. This should be fixed.

      // Check all vars after finishing a scope
      Scope scope = t.getScope();
      if (scope.isFunctionBlockScope()) {
        varsInFunctionBody.clear();
        for (Var v : scope.getVarIterable()) {
          varsInFunctionBody.add(v.name);
        }
      }
      for (Var v : scope.getVarIterable()) {
        ReferenceCollection referenceCollection = referenceMap.getReferences(v);
        // TODO(moz): Figure out why this could be null
        if (referenceCollection != null) {
          if (scope.getRootNode().isFunction() && v.isDefaultParam()) {
            checkDefaultParam(v, scope, varsInFunctionBody);
          }
          if (scope.getRootNode().isFunction()) {
            checkShadowParam(v, scope, referenceCollection.references);
          }
          checkVar(v, referenceCollection.references);
        }
      }
    }

    private void checkDefaultParam(
        Var param, final Scope scope, final Set<String> varsInFunctionBody) {
      NodeTraversal.traverseEs6(
          compiler,
          param.getParentNode().getSecondChild(),
          /**
           * Do a shallow check since cases like:
           *   function f(y = () => x, x = 5) { return y(); }
           * is legal. We are going to miss cases like:
           *   function f(y = (() => x)(), x = 5) { return y(); }
           * but this should be rare.
           */
          new AbstractShallowCallback() {
            @Override
            public void visit(NodeTraversal t, Node n, Node parent) {
              if (!NodeUtil.isReferenceName(n)) {
                return;
              }
              String refName = n.getString();
              if (varsInFunctionBody.contains(refName) && !scope.isDeclared(refName, true)) {
                compiler.report(JSError.make(n, EARLY_REFERENCE_ERROR, refName));
              }
            }
          });
    }

    private void checkShadowParam(Var v, Scope functionScope, List<Reference> references) {
      Var maybeParam = functionScope.getVar(v.getName());
      if (maybeParam != null && maybeParam.isParam() && maybeParam.getScope() == functionScope) {
        for (Reference r : references) {
          if ((r.isVarDeclaration() || r.isHoistedFunction())
              && r.getNode() != v.getNameNode()) {
            compiler.report(JSError.make(r.getNode(), REDECLARED_VARIABLE, v.name));
          }
        }
      }
    }

    /**
     * If the variable is declared more than once in a basic block, generate a
     * warning. Also check if a variable is used in a given scope before it is
     * declared, which suggest a likely error. Relies on the fact that
     * references is in parse-tree order.
     */
    private void checkVar(Var v, List<Reference> references) {
      blocksWithDeclarations.clear();
      boolean isDeclaredInScope = false;
      boolean isUnhoistedNamedFunction = false;
      boolean hasErrors = false;
      boolean isRead = false;
      Reference hoistedFn = null;
      Reference unusedAssignment = null;

      // Look for hoisted functions.
      for (Reference reference : references) {
        if (reference.isHoistedFunction()) {
          blocksWithDeclarations.add(reference.getBasicBlock());
          isDeclaredInScope = true;
          hoistedFn = reference;
          break;
        } else if (NodeUtil.isFunctionDeclaration(reference.getNode().getParent())) {
          isUnhoistedNamedFunction = true;
        }
      }

      for (Reference reference : references) {
        if (reference == hoistedFn) {
          continue;
        }
        BasicBlock basicBlock = reference.getBasicBlock();
        boolean isDeclaration = reference.isDeclaration();
        Node referenceNode = reference.getNode();
        boolean isAssignment = isDeclaration || reference.isLvalue();

        boolean allowDupe = VarCheck.hasDuplicateDeclarationSuppression(referenceNode, v);
        boolean letConstShadowsVar =
            v.getParentNode().isVar()
                && (reference.isLetDeclaration() || reference.isConstDeclaration());
        boolean isVarNodeSameAsReferenceNode = v.getNode() == reference.getNode();
        // We disallow redeclaration of caught exceptions
        boolean shadowCatchVar =
            isDeclaration
                && v.getParentNode().isCatch()
                && !isVarNodeSameAsReferenceNode;
        boolean shadowParam =
            isDeclaration
                && v.isParam()
                && NodeUtil.isBlockScopedDeclaration(referenceNode)
                && v.getScope() == reference.getScope().getParent();
        boolean shadowDetected = false;
        if (isDeclaration && !allowDupe) {
          // Look through all the declarations we've found so far, and
          // check if any of them are before this block.
          for (BasicBlock declaredBlock : blocksWithDeclarations) {
            if (declaredBlock.provablyExecutesBefore(basicBlock)) {
              shadowDetected = true;
              DiagnosticType diagnosticType;
              Node warningNode = referenceNode;
              if (v.isLet()
                  || v.isConst()
                  || v.isClass()
                  || letConstShadowsVar
                  || shadowCatchVar
                  || shadowParam) {
                // These cases are all hard errors that violate ES6 semantics
                diagnosticType = REDECLARED_VARIABLE_ERROR;
              } else if (reference.getNode().getParent().isCatch() || allowDupe) {
                return;
              } else {
                // These diagnostics are for valid, but suspicious, code, and are suppressible.
                // For vars defined in the global scope, give the same error as VarCheck
                diagnosticType =
                    v.getScope().isGlobal()
                        ? VarCheck.VAR_MULTIPLY_DECLARED_ERROR
                        : REDECLARED_VARIABLE;
                // Since we skip hoisted functions, we would have the wrong warning node in cases
                // where the redeclaration is a function declaration. Check for that case.
                if (isVarNodeSameAsReferenceNode
                    && hoistedFn != null
                    && v.name.equals(hoistedFn.getNode().getString())) {
                  warningNode = hoistedFn.getNode();
                }
              }
              compiler.report(
                  JSError.make(
                      warningNode,
                      diagnosticType,
                      v.name,
                      v.input != null ? v.input.getName() : "??"));
              hasErrors = true;
              break;
            }
          }
        }

        if (!shadowDetected
            && isDeclaration
            && (letConstShadowsVar || shadowCatchVar)
            && v.getScope() == reference.getScope()) {
          compiler.report(JSError.make(referenceNode, REDECLARED_VARIABLE_ERROR, v.name));
        }

        if (isAssignment) {
          Reference decl = references.get(0);
          Node declNode = decl.getNode();
          Node gp = declNode.getGrandparent();
          boolean lhsOfForInLoop = gp.isForIn() && gp.getFirstFirstChild() == declNode;

          if (decl.getScope().isLocal()
              && (decl.isVarDeclaration() || decl.isLetDeclaration() || decl.isConstDeclaration())
              && !decl.getNode().isFromExterns()
              && !lhsOfForInLoop) {
            unusedAssignment = reference;
          }
          if ((reference.getParent().isDec() || reference.getParent().isInc())
              && NodeUtil.isExpressionResultUsed(reference.getNode())) {
            isRead = true;
          }
        } else {
          isRead = true;
        }

        if (isUnhoistedNamedFunction && !isDeclaration && isDeclaredInScope) {
          // Only allow an unhoisted named function to be used within the
          // block it is declared.
          for (BasicBlock declaredBlock : blocksWithDeclarations) {
            if (!declaredBlock.provablyExecutesBefore(basicBlock)) {
              compiler.report(JSError.make(referenceNode, AMBIGUOUS_FUNCTION_DECL, v.name));
              hasErrors = true;
              break;
            }
          }
        }

        boolean isUndeclaredReference = false;
        if (!isDeclaration && !isDeclaredInScope) {
          // Don't check the order of refer in externs files.
          if (!referenceNode.isFromExterns()) {
            // Special case to deal with var goog = goog || {}. Note that
            // let x = x || {} is illegal, just like var y = x || {}; let x = y;
            if (v.isVar()) {
              Node curr = reference.getParent();
              while (curr.isOr() && curr.getParent().getFirstChild() == curr) {
                curr = curr.getParent();
              }
              if (curr.isName() && curr.getString().equals(v.name)) {
                continue;
              }
            }

            // Only generate warnings if the scopes do not match in order
            // to deal with possible forward declarations and recursion
            // TODO(moz): Remove the bypass for "goog" once VariableReferenceCheck
            // is run after the Closure passes.
            if (reference.getScope() == v.scope && !v.getName().equals("goog")) {
              isUndeclaredReference = true;
              compiler.report(
                  JSError.make(
                      reference.getNode(),
                      (v.isLet() || v.isConst() || v.isClass() || v.isParam())
                          ? EARLY_REFERENCE_ERROR
                          : EARLY_REFERENCE,
                      v.name));
              hasErrors = true;
            }
          }
        }

        if (!isDeclaration && !isUndeclaredReference && v.isConst() && reference.isLvalue()) {
          compiler.report(JSError.make(referenceNode, REASSIGNED_CONSTANT, v.name));
        }

        if (isDeclaration
            && !reference.isVarDeclaration()
            && reference.getGrandparent().isAddedBlock()
            && BLOCKLESS_DECLARATION_FORBIDDEN_STATEMENTS.contains(
                reference.getGrandparent().getParent().getToken())) {
          compiler.report(JSError.make(referenceNode, DECLARATION_NOT_DIRECTLY_IN_BLOCK, v.name));
        }

        if (isDeclaration) {
          blocksWithDeclarations.add(basicBlock);
          isDeclaredInScope = true;
        }
      }

      if (unusedAssignment != null && !isRead && !hasErrors) {
        checkForUnusedLocalVar(v, unusedAssignment);
      }
    }
  }

  // Only check for unused local if not in a goog.scope function.
  // TODO(tbreisacher): Consider moving UNUSED_LOCAL_ASSIGNMENT into its own check pass, so
  // that we can run it after goog.scope processing, and get rid of the inGoogScope check.
  private void checkForUnusedLocalVar(Var v, Reference unusedAssignment) {
    JSDocInfo jsDoc = NodeUtil.getBestJSDocInfo(unusedAssignment.getNode());
    if (jsDoc != null && jsDoc.hasTypedefType()) {
      return;
    }

    boolean inGoogScope = false;
    Scope s = v.getScope();
    Node function = null;
    if (s.isFunctionBlockScope()) {
      function = s.getRootNode().getParent();
    } else if (s.isFunctionScope()) {
      // TODO(tbreisacher): Remove this branch when everything is switched to
      // Es6SyntacticScopeCreator.
      function = s.getRootNode();
    }
    if (function != null) {
      Node callee = function.getPrevious();
      inGoogScope = callee != null && callee.matchesQualifiedName("goog.scope");
    }

    if (inGoogScope) {
      // No warning.
      return;
    }

    if (s.isModuleScope()) {
      Node statement = NodeUtil.getEnclosingStatement(v.getNode());
      if (NodeUtil.isNameDeclaration(statement)) {
        Node lhs = statement.getFirstChild();
        Node rhs = lhs.getFirstChild();
        if (rhs != null
            && rhs.isCall()
            && rhs.getFirstChild().matchesQualifiedName("goog.require")) {
          // No warning. Will be caught by the unused-require check anyway.
          return;
        }
      }
    }

    compiler.report(JSError.make(unusedAssignment.getNode(), UNUSED_LOCAL_ASSIGNMENT, v.name));
  }
}
