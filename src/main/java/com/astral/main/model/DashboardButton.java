package com.astral.main.model;

/**
 * Representa um botao do dashboard da Astral Platform.
 *
 * @param id     identificador curto do card
 * @param label  texto exibido (usado como alt/title da imagem)
 * @param image  nome do arquivo em /static/images (ex: "dns_management.png")
 * @param route  rota absoluta ja tratada pelo Nginx (ex: "/dns"), ou uma
 *               ancora (ex: "#terminal") para cards que sao interceptados
 *               em JavaScript e nao navegam de verdade
 */
public record DashboardButton(String id, String label, String image, String route) {
}
