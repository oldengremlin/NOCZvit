/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.ukrcom.noczvit;

/**
 *
 * @author olden
 */
public class Debtors {

    private StringBuilder returnMessage;

    public Debtors() {
        this.returnMessage = new StringBuilder();
        this.getDebtors();
    }

    @Override
    public String toString() {
        return this.returnMessage.toString();
    }

    private void getDebtors() {
        this.returnMessage.append("<p><ol><h1><small><small>Список тимчасово заблокованих абонентів</small></small></h1>");

        this.returnMessage
                .append("<li style=\"margin-left: 50px;\">")
                .append("Вася Пупкін")
                .append("</li>");

        this.returnMessage.append("</ol><p>");
    }
}
