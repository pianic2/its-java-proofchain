package it.itsprodigi.proofchain.common.exception;

record ValidationError(String field, String message, String code) {
}
