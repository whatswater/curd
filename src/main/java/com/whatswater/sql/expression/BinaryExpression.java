package com.whatswater.sql.expression;

public abstract class BinaryExpression implements Expression {
    private Expression left;
    private Expression right;

    public BinaryExpression(Expression left, Expression right) {
        this.left = left;
        this.right = right;
    }

    public Expression getLeft() {
        return left;
    }

    public void setLeft(Expression left) {
        this.left = left;
    }

    public Expression getRight() {
        return right;
    }

    public void setRight(Expression right) {
        this.right = right;
    }

    @Override
    public String toString() {
        return this.getLeft() + " " + this.getOperatorString() + " " + this.getRight();
    }

    public abstract String getOperatorString();

    @Override
    public void visitAliasHolder(Handler handler) {
        left.visitAliasHolder(handler);
        right.visitAliasHolder(handler);
    }
}
